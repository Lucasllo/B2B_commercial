# A matriz regra → teste

Arquivos (plano 07-10):

- `.planning/phases/07-endurecimento-observabilidade-e-entrega/07-COVERAGE.md` — a matriz
- `scripts/check-coverage-matrix.sh` — a verificação automática
- `.github/workflows/ci.yml` — o passo que roda o script no job `guardas`
- os testes unitários novos citados no fim desta nota

Continuação de [15-testes.md](15-testes.md), que explica os tipos de teste do projeto.

## O problema: como provar "sem lacunas"?

O critério 4 da Fase 7 pede que "todo serviço tem testes unitários das regras centrais e ao menos
um teste de integração contra dependência real, sem lacunas herdadas". A resposta mais comum seria
uma porcentagem de cobertura de linhas (o JaCoCo diz "87% das linhas foram executadas").

O projeto decidiu **não** usar porcentagem (D-105). O motivo: uma linha executada não é uma regra
testada. Um teste pode passar por um `if` sem nunca conferir o resultado dele, e a linha conta como
coberta. Dá para chegar a 90% sem que nenhum teste quebre se alguém trocar `<=` por `<` na regra de
crédito.

A alternativa é uma **matriz**: uma tabela com uma linha por regra de negócio, e em cada linha o
nome do teste que prova aquela regra. É a diferença entre "o fiscal visitou 87% dos cômodos" e
"para cada item da planta, aqui está a foto do item instalado".

## O formato da matriz

O arquivo `07-COVERAGE.md` tem uma seção `## <módulo>` para cada um dos sete módulos
(`auth-service`, `catalog-service`, `inventory-service`, `notification-service`, `order-service`,
`gateway`, `e2e-tests`). Nos seis primeiros, a tabela tem estas colunas:

| # | Regra central | Origem | Teste unitário | Teste de integração (dependência real) | Status |
|---|---|---|---|---|---|

Uma linha real, do order-service:

```markdown
| 1 | Limite de crédito por exposição acumulada: a igualdade aprova (compareTo, não equals) | ORD-02, D-36 | `OrderDomainTest#fitsWithinLimitApprovesAtExactEqualityEvenWithDifferentScales` | `OrderControllerIT#buyerCreatesOrderValidatedAgainstCatalogAndDecidedByCreditLimitBoundary`, `CreditLimitBoundaryConcurrencyIT#limite100ComDezContendoresDeSessenta_exatamenteUmAprovado` | coberta |
```

Coluna por coluna:

- **Regra central** — o que o `REQUIREMENTS.md` e as decisões `D-xx` tornaram regra de negócio ou
  de contrato: limites, estados, idempotência, isolamento por empresa, atomicidade, propagação do
  Correlation-ID. Getter, DTO e configuração sem lógica **não** entram.
- **Origem** — de onde a regra veio: um requisito (`ORD-02`) e/ou uma decisão (`D-36`).
- **Teste unitário** — sem Docker (JUnit 5 + Mockito/AssertJ). Escrito como `NomeDaClasse` ou
  `NomeDaClasse#metodo`.
- **Teste de integração** — contra dependência **real**: Postgres e LocalStack via
  Testcontainers. No gateway, que não tem banco nem fila, um servidor HTTP real num socket.
- **Status** — `coberta`, `fechada em 07-NN` (a lacuna foi fechada por aquele plano) ou `LACUNA`.

A seção `e2e-tests` é um pouco diferente, porque ali cada linha é um **fluxo entre serviços**, não
uma regra de um serviço só:

| # | Fluxo entre serviços | Origem | Teste E2E | Status |
|---|---|---|---|---|

Ao todo, a matriz tem 72 regras em sete módulos.

Uma escolha registrada no cabeçalho do arquivo: as regras de segurança por serviço (401 para token
inválido, 403 para papel errado) são provadas só por teste de integração e não viram linha própria.
Elas só aparecem quando existe também um unitário da decisão — como o `CompanyGuard`, o filtro do
Correlation-ID ou as regras de visibilidade.

## O que significa `LACUNA`

`LACUNA` é a linha que admite: "esta regra existe, e nenhum teste a prova nas duas camadas". Em
geral era uma regra provada **só** por integração — o IT funcionava, mas não havia um unitário que
isolasse a regra.

Escrever `LACUNA` em vez de esconder a linha é o ponto da matriz. A lista do que **falta** fica
visível, e o trabalho vira objetivo: transformar cada `LACUNA` em `fechada em 07-10`.

Hoje nenhuma linha da matriz termina como `LACUNA`.

## Como o `check-coverage-matrix.sh` funciona

Uma tabela escrita à mão mente com facilidade: alguém renomeia um método de teste e a matriz
continua citando o nome antigo. O script lê o `07-COVERAGE.md` linha a linha e confere, sem nenhuma
dependência além de `grep`, `sed` e `git`:

1. Existe a seção `## <módulo>` dos sete módulos, e cada uma tem ao menos uma regra.
2. Para cada nome entre crases terminado em `Test` ou `IT`, existe o arquivo `<Nome>.java` em
   `<módulo>/src/test/java` — **do módulo daquela seção**, porque há classes com o mesmo nome em
   vários módulos (`CorrelationIdFilterTest`, `OutboxRelayTest`).
3. Se o nome tem `#metodo`, o método existe no arquivo. A busca é literal:

   ```bash
   if ! grep -qE "void $method\(" "$file"; then
       problem "metodo inexistente $name ($module #$row)"
   ```

4. Toda linha de serviço cita **pelo menos um** unitário e **pelo menos um** teste de integração;
   toda linha de `e2e-tests` cita um teste E2E.
5. O status é `coberta`, `fechada em 07-NN` ou `LACUNA` — qualquer outra coisa é erro.

Linhas `LACUNA` não têm os nomes conferidos: o teste ainda não existe, por definição.

### Modo estrito e modo `--report`

```bash
bash scripts/check-coverage-matrix.sh            # estrito: falha se um teste citado não existir ou restar LACUNA
bash scripts/check-coverage-matrix.sh --report   # lista as LACUNA sem falhar por elas
```

- **Estrito** (o padrão) — uma `LACUNA` é um problema: `COVERAGE CHECK FALHOU: lacuna aberta: <módulo> #<n>`,
  e o script sai com erro.
- **`--report`** — cada `LACUNA` vira uma linha informativa `LACUNA: <módulo> #<n> <regra>`, e o
  script não falha por ela. Os outros problemas (teste inexistente, coluna vazia) continuam
  falhando.

O `--report` é a ferramenta de **trabalho**: enquanto as lacunas estão sendo fechadas, ele mostra
a lista do que falta sem travar tudo. O estrito é a ferramenta de **garantia**: depois que a matriz
fica limpa, ninguém pode reabrir uma lacuna sem o build reclamar.

Sem problemas, a última linha é `COVERAGE CHECK OK <n> regras`.

## Como o CI usa

O job `guardas` do `.github/workflows/ci.yml` roda as três verificações que não precisam de Docker:

```yaml
# Toda regra central tem teste unitario e de integracao na matriz (TEST-01/TEST-02).
- run: bash scripts/check-coverage-matrix.sh
```

Sem argumento, ou seja, em modo **estrito**. Se alguém apagar ou renomear um teste citado, ou
voltar uma linha para `LACUNA`, o run fica vermelho, com o problema escrito na tela. Ver
[29-github-actions-ci.md](29-github-actions-ci.md).

Repare no que o script **não** faz: ele não roda os testes, só confere que eles existem. Quem prova
que passam é o `./mvnw verify` dos outros jobs. Um confere o mapa; o outro, o território.

## Lacunas que a matriz achou e que foram fechadas

A matriz foi montada **depois** dos planos de teste da fase, como uma auditoria do estado final
(plano 07-10). O que ela acusou foi fechado no mesmo plano com testes unitários novos, sem Docker:

- **inventory-service** — `InventoryTest` e `StockReservationTest` (as contas de físico, reservado
  e disponível; a lápide) e `InventoryServiceTest` (reserva atômica, idempotência, multi-item
  tudo-ou-nada, baixa pelo livro, ajuste de estoque). Exemplo: a regra "Liberar nunca deixa o
  reservado negativo" ganhou `InventoryTest#releaseNeverLeavesReservedNegative`.
- **order-service** — `OrderServiceTest`, `OrderDecisionServiceTest`, `OrderShipmentServiceTest` e
  `OrderSagaServiceTest`. Exemplo: "a trava da empresa é adquirida antes de ler a exposição" ganhou
  `OrderServiceTest#theCompanyLockIsAcquiredBeforeTheExposureIsRead` — uma regra de **ordem** de
  chamadas, que um IT dificilmente isola.
- **auth-service** — `AuthControllerTest`. Exemplo:
  `AuthControllerTest#theUnauthorizedBodyIsTheSameForAnyAuthenticationExceptionSoUnknownEmailAndWrongPasswordLookAlike`
  (o 401 não revela se o e-mail existe).
- **gateway** — `GatewayRoutesTest` (a tabela de rotas estáticas) e dois casos novos em
  `CorrelationIdFilterTest` (a linha de acesso sem query string e sem `Authorization`; o health sem
  linha).

Antes disso, no plano 07-05, auth e catalog já tinham ganhado `TokenServiceTest`,
`CompanyGuardTest`, `CompanyServiceTest` e `ProductServiceTest` — as regras centrais dos dois
serviços que até então só tinham `*IT.java`.

Nas linhas da matriz, essas regras aparecem com status `fechada em 07-10`. Elas já tinham IT;
ganharam o unitário que faltava.
