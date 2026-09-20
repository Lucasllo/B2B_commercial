# Spring Retry (`@Retryable`, `@Recover`, `@EnableRetry`)

Arquivos: `inventory-service/src/main/java/com/orderflow/inventory/config/RetryConfig.java`
e `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java`.

## O problema que o Spring Retry resolve

Imagina duas requisições chegando quase ao mesmo tempo para reservar estoque do
**mesmo produto**. O banco usa "lock otimista" (via um campo de versão na linha):
cada uma lê a linha, mas só uma consegue gravar sua alteração — a outra recebe
`ObjectOptimisticLockingFailureException`, porque a versão que ela leu já não é
mais a versão atual.

Sem nenhum tratamento especial, essa segunda requisição simplesmente falharia com
um erro técnico, mesmo havendo estoque de sobra para as duas. O Spring Retry
resolve isso automatizando o que um humano faria manualmente: **tentar de novo**,
relendo o dado atualizado, até dar certo ou esgotar um número razoável de
tentativas.

## `@EnableRetry` e a classe `RetryConfig` — o interruptor

```java
@Configuration
@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)
public class RetryConfig {
}
```

Essa classe está vazia — não tem métodos, só duas anotações. Isso é comum no
Spring: uma classe de configuração cujo único trabalho é "ligar um interruptor".

`@Retryable`, que você vai ver logo abaixo, é só uma **etiqueta** num método —
sozinha ela não faz nada. Quem de fato implementa o comportamento de reexecução
(interceptar a chamada, tentar de novo, aplicar espera entre tentativas) é o
mecanismo de AOP (Aspect-Oriented Programming) do Spring Retry, e ele só entra em
ação se `@EnableRetry` existir em algum lugar da aplicação. Sem essa classe, todo
`@Retryable` do projeto seria ignorado silenciosamente — sem erro nenhum, os
métodos simplesmente falhariam na primeira tentativa.

### Por que `order = Ordered.LOWEST_PRECEDENCE - 1`, e não o padrão

Isso não é cosmético — é a parte mais sutil do arquivo, e o comentário Javadoc da
própria classe explica o motivo. Tanto o retry quanto `@Transactional` funcionam
através de "proxies" que o Spring coloca ao redor do método real. A ordem entre
eles importa:

**Jeito certo** (o que essa configuração garante):
```
Retry (por fora)
  └── Transação (por dentro)
        └── reserve(...)
```
Cada nova tentativa cria uma transação **nova**, que relê a linha do banco já com
a versão atualizada — é isso que dá ao retry uma chance real de funcionar.

**Jeito errado**, se a ordem não fosse garantida:
```
Transação (por fora)
  └── Retry (por dentro)
        └── reserve(...)
```
Todas as tentativas aconteceriam dentro da **mesma** transação, já condenada pelo
primeiro erro de versão — a segunda tentativa morreria na hora, sem nunca reler o
dado novo. O retry existiria no papel, mas nunca funcionaria de verdade, e isso
não geraria erro algum: seria um bug silencioso.

`Ordered.LOWEST_PRECEDENCE - 1` é um valor estritamente **abaixo** de
`Ordered.LOWEST_PRECEDENCE` — o valor padrão que `@EnableTransactionManagement`
usa (confirmado por inspeção de bytecode, segundo o comentário do arquivo). Quanto
menor o número, mais "por fora" o aspecto fica. Por isso esse valor garante, sem
depender de coincidência na ordem de registro dos beans, que o retry sempre
envolve a transação por fora.

## `@Retryable` — tentando de novo automaticamente

```java
@Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class},
        maxAttempts = RETRY_MAX_ATTEMPTS,
        backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS))
@Transactional
public StockResponse reserve(UUID productId, String reservationId, int quantity) {
    ...
}
```

`InventoryService` usa essa anotação em três métodos — `reserve`, `release` e
`setStock` — sempre com os mesmos parâmetros:

- **`retryFor = {...}`** — só reexecuta se a exceção lançada for
  `ObjectOptimisticLockingFailureException` (conflito de versão) ou
  `DataIntegrityViolationException` (violação de constraint — por exemplo, duas
  requisições tentando criar a mesma linha de inventário ou a mesma reserva ao
  mesmo tempo). Qualquer outra exceção — como `InsufficientStockException`,
  estoque insuficiente — **não** dispara retry, porque é um erro de negócio
  legítimo, não uma corrida entre transações concorrentes.
- **`maxAttempts = 10`** — até 10 tentativas no total.
- **`backoff = @Backoff(...)`** — espera entre tentativas: `delay = 20`ms na
  primeira reexecução, `multiplier = 2.0` (dobra a cada vez: 20ms, 40ms, 80ms...),
  `maxDelay = 200`ms (nunca espera mais que isso, para o pior caso não ficar lento
  demais).

O comentário do método `setStock` no código explica por que esses valores mudaram
de um `maxAttempts = 4` inicial: um teste de concorrência
(`InventoryRetryContentionIT`, dez threads reservando 1 unidade cada sobre um
estoque de dez) mostrou que 4 tentativas não bastavam sob dez gravadores
simultâneos na mesma linha — mesmo havendo estoque suficiente para todo mundo,
algumas tentativas esgotavam o retry. Aumentar para 10 tentativas com um teto de
backoff resolveu isso sem deixar o pior caso lento (sem o teto, a décima tentativa
esperaria mais de 12 segundos).

## `@Recover` — o plano B quando todas as tentativas falham

```java
@Recover
public StockResponse recoverReserve(DataAccessException ex, UUID productId, String reservationId, int quantity) {
    throw new ReservationConflictException();
}
```

Se as 10 tentativas se esgotarem sem sucesso, o Spring **não propaga** a exceção
técnica original para quem chamou. Em vez disso, ele procura um método `@Recover`
compatível e o chama.

O Spring casa o método `@Recover` com o `@Retryable` original pela **assinatura**:
o primeiro parâmetro precisa ser um tipo de exceção compatível com as declaradas
em `retryFor` (aqui, `DataAccessException` — superclasse tanto de
`ObjectOptimisticLockingFailureException` quanto de
`DataIntegrityViolationException`), e os parâmetros seguintes precisam bater com
os parâmetros do método original (`UUID productId, String reservationId, int
quantity`). Se você mudar os parâmetros de `reserve`, precisa mudar
`recoverReserve` junto — senão o Spring não consegue casar os dois e a aplicação
falha ao subir.

`InventoryService` tem um `@Recover` para cada método com `@Retryable`
(`recoverReserve`, `recoverRelease`, `recoverSetStock`), e todos fazem a mesma
coisa: lançam `ReservationConflictException` — uma exceção de negócio, mapeada
para um HTTP 409 (Conflict) — em vez de deixar vazar para a API um detalhe de
implementação (`DataAccessException`, um tipo de exceção de banco de dados). Sem
o `@Recover`, o cliente da API veria um erro técnico de infraestrutura em vez de
uma resposta de negócio clara.

## Uma regra estrutural que este projeto documenta explicitamente

O comentário no topo de `InventoryService` deixa um aviso importante: `reserve`,
`release` e `setStock` só funcionam com retry e transação quando chamados **de
fora da classe** (pelo `InventoryController`, por exemplo). Se um método dentro de
`InventoryService` chamasse `this.reserve(...)` internamente, o Spring **não**
aplicaria nem o retry nem a transação — porque essas anotações funcionam através
de um proxy que o Spring cria ao redor do bean, e uma chamada interna
(`this.metodo()`) pula esse proxy inteiramente.

Isso não gera erro de compilação nem exceção em tempo de execução — é um
comportamento errado silencioso. Por isso é um cuidado a manter ao adicionar
código novo nessa classe: qualquer nova lógica que precise reaproveitar
`reserve`/`release`/`setStock` deve chamá-los de fora, nunca internamente.

## Resumindo o trio

- **`@EnableRetry`** (em `RetryConfig`) = liga o mecanismo de retry para o
  projeto inteiro, e garante que ele envolva as transações por fora.
- **`@Retryable`** = "se esse método falhar com um desses erros específicos, tenta
  de novo automaticamente, até N vezes, com espera crescente entre tentativas."
- **`@Recover`** = "se mesmo depois de N tentativas continuar falhando, é aqui que
  eu decido o que fazer" — geralmente traduzir o erro técnico numa exceção de
  negócio clara.
