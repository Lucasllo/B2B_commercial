# Records e suas anotações

Arquivos: os DTOs (`*Request.java` / `*Response.java`) em
`auth-service/src/main/java/com/orderflow/auth/*/dto/`

## O que é um `record`, em uma frase

`record` é um tipo especial de classe em Java, feito especificamente para representar
"um pacotinho de dados que não muda depois de criado" — como um formulário preenchido:
uma vez que você escreveu o nome e o email nele, esses dados ficam fixos.

## O problema que o `record` resolve

Antes do `record` existir (chegou no Java 16), para criar uma classe simples que só
carrega dados, era preciso escrever manualmente: os campos, um construtor, um "getter"
para cada campo, e os métodos `equals()`, `hashCode()` e `toString()`. Muito código
repetitivo para algo tão simples quanto "guardar um nome e um email".

O `record` faz tudo isso **automaticamente**, só com uma linha:

```java
public record LoginRequest(
        String email,
        String password
) {
}
```

Essa única declaração já dá de graça: um construtor `LoginRequest(email, password)`,
métodos de acesso `email()` e `password()` (sem o prefixo `get`, diferente do padrão
antigo), além de `equals()`, `hashCode()` e `toString()` prontos.

## Por que "imutável" importa aqui

Depois de criado um `record`, não é possível mudar os valores de dentro dele (não
existe `setEmail(...)`). Isso é proposital: um `record` representa um "retrato" fixo de
um dado num momento específico — exatamente o que o cliente enviou numa requisição, ou
exatamente o que o servidor está devolvendo. Não faz sentido alterar esses dados no meio
do caminho; eles só existem para ir de um lado a outro.

## Onde os `records` aparecem no projeto — DTOs

Todos os `records` do `auth-service` são **DTOs** (Data Transfer Objects) — o formato
exato do corpo (`body`) de uma requisição ou resposta HTTP em JSON:

- `LoginRequest` / `LoginResponse` — `POST /auth/login`
- `CurrentUserResponse` — `GET /auth/me`
- `CreateCompanyRequest` / `CompanyResponse` — `POST /companies`
- `UpdateCreditLimitRequest` / `CreditLimitResponse` — o endpoint protegido pelo
  `CompanyGuard` (ver [11-companyguard.md](11-companyguard.md))

## Records aninhados (um `record` dentro de outro)

```java
public record CreateCompanyRequest(
        String name,
        BigDecimal creditLimit,
        @Valid BuyerUser buyerUser
) {
    public record BuyerUser(String email, String password) {
    }
}
```

`BuyerUser` é um `record` declarado **dentro** de `CreateCompanyRequest`, porque só faz
sentido no contexto daquela requisição específica (um usuário comprador sendo criado
junto com a empresa, numa única chamada). O mesmo padrão aparece em `CompanyResponse`,
com `BuyerUserSummary` aninhado. Isso evita criar arquivos `.java` separados para
pedacinhos de dados que só existem "dentro" de outro.

## As anotações usadas nos records do projeto

Anotações são "etiquetas" coladas em cima de um campo, dizendo a alguma ferramenta
(Jackson, Spring, Hibernate Validator) "trate esse dado de um jeito especial".

### `@NotBlank`
```java
@NotBlank String email
```
Rejeita o valor se ele for `null`, vazio (`""`) ou só espaços em branco.

### `@NotNull`
```java
@NotNull BigDecimal creditLimit
```
Rejeita apenas se o valor for `null` — mas aceita, por exemplo, uma string vazia (por
isso `@NotBlank` é usado em textos, e `@NotNull` em números/objetos sem conceito de
"vazio").

### `@Size(max = 255)` / `@Size(min = 8)`
```java
@Size(max = 255) String name
@Size(min = 8) String password
```
Limita o comprimento de um texto. `max` define um teto (provavelmente o limite da
coluna no banco — ver [08-flyway-migrations.md](08-flyway-migrations.md)); `min` define
um piso.

### `@Email`
```java
@Email String email
```
Confere se o texto tem um formato parecido com um endereço de email válido.

### `@DecimalMin(value = "0.00")`
```java
@DecimalMin(value = "0.00") BigDecimal creditLimit
```
Rejeita números menores que o valor indicado — garante que um limite de crédito nunca
seja negativo.

### `@Digits(integer = 17, fraction = 2)`
```java
@Digits(integer = 17, fraction = 2) BigDecimal creditLimit
```
Controla quantas casas cada parte de um número decimal pode ter: até 17 dígitos antes
da vírgula, e **exatamente até 2** depois. É o que impede alguém de enviar `100.999`
como limite de crédito — rejeita com erro (400) em vez de arredondar silenciosamente,
o que poderia mascarar um erro do cliente.

### `@Valid`
```java
@NotNull @Valid BuyerUser buyerUser
```
Diferente dos outros: não valida o próprio campo, diz "entre dentro desse objeto
aninhado e valide as anotações que existem lá dentro também". Sem o `@Valid`, as
anotações de `BuyerUser.email` e `BuyerUser.password` seriam ignoradas — por padrão, a
validação não desce automaticamente para dentro de objetos aninhados.

### O que acontece quando uma validação falha

Todas essas anotações fazem parte da especificação **Bean Validation**. Quando um
`record` anotado com essas regras é usado num controller marcado com `@Valid`, o Spring
confere todas as regras automaticamente **antes** do código do método rodar. Se alguma
falhar, é lançada `MethodArgumentNotValidException` — tratada no
[09-global-exception-handler.md](09-global-exception-handler.md), devolvendo `400 Bad
Request` com a lista de campos inválidos.

### `@JsonIgnoreProperties(ignoreUnknown = true)`

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateCompanyRequest(...)
```

Diferente das outras — não é do Bean Validation, é do **Jackson** (biblioteca que
converte JSON em objetos Java e vice-versa). Diz: "se o JSON recebido tiver algum campo
extra que esse `record` não conhece, simplesmente ignore, não dê erro".

Motivo de segurança: sem essa anotação, se um cliente mal-intencionado enviasse um
campo extra como `"role": "SELLER_ADMIN"` esperando se auto-promover a administrador, o
Jackson lançaria um erro de parsing, derrubando a requisição inteira. Além disso, o
campo `role` **nem existe** na declaração do `record` — o servidor sempre decide
sozinho, no código (`CompanyService`), que todo usuário criado por esse endpoint é
`BUYER`. Esse tipo de ataque (enviar campos extras esperando que o servidor os aceite
sem querer) é chamado de **"mass assignment"**; essa anotação é uma das defesas contra
ele — o campo malicioso é silenciosamente descartado, sem derrubar a requisição e sem
nenhum efeito no sistema.
