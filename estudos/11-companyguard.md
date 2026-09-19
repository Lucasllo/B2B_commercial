# CompanyGuard

Arquivo: `auth-service/src/main/java/com/orderflow/auth/company/CompanyGuard.java`

## O que essa classe faz, em uma frase

`CompanyGuard` é um "porteiro" que decide se o usuário logado pode ver ou mexer nos
dados de uma empresa específica — ele existe para impedir que uma empresa compradora
consiga espiar ou alterar informações de outra empresa (por exemplo, o limite de
crédito).

## O contexto: por que isso é necessário

Este é um sistema **multi-empresa** (multi-tenant): várias empresas compradoras
diferentes usam o mesmo sistema, mas cada uma só deve enxergar os próprios dados. Se a
empresa A conseguisse ver o limite de crédito da empresa B só trocando um número na URL
(`/companies/{outroId}/credit-limit`), isso seria uma falha grave de segurança —
chamada de "IDOR" (acesso indevido a um recurso trocando o identificador na URL).

`CompanyGuard` é o componente responsável por bloquear exatamente esse cenário.

## Onde ele é usado

```java
@GetMapping("/{companyId}/credit-limit")
@PreAuthorize("@companyGuard.isSelfOrSeller(#companyId)")
public CreditLimitResponse getCreditLimit(@PathVariable UUID companyId) {
```

`@PreAuthorize` diz: "antes de deixar esse método do controller rodar, primeiro confira
essa condição — se for falsa, bloqueia com `403 Forbidden` automaticamente, sem nem
executar o código do método".

`@companyGuard.isSelfOrSeller(#companyId)` é escrito em **SpEL** (Spring Expression
Language) — um jeitinho de escrever pequenas expressões dentro de anotações. Aqui ela
diz: "chame o método `isSelfOrSeller`, passando o `companyId` que veio na URL, no
componente chamado `companyGuard`".

## `@Component("companyGuard")` — por que o nome importa

```java
@Component("companyGuard")
public class CompanyGuard {
```

`@Component` diz ao Spring: "essa classe é uma peça que você deve criar
automaticamente e deixar disponível para uso" (o mesmo mecanismo do `@Bean` visto no
`SecurityConfig`, ver [05-security-config.md](05-security-config.md), só que aplicado
direto na classe em vez de num método de configuração).

O nome `"companyGuard"` entre parênteses precisa bater **exatamente** com o nome usado
no `@PreAuthorize("@companyGuard...")` do controller — é assim que o Spring conecta a
expressão escrita em texto com o objeto Java real na memória.

## O método `isSelfOrSeller(UUID companyId)` — a lógica de decisão

```java
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
```
`SecurityContextHolder` é onde o Spring Security guarda "quem está logado nessa
requisição, agora" (já validado pelo mecanismo JWT, ver [06-jwks.md](06-jwks.md)).

```java
if (authentication == null) {
    return false;
}
```
Se não houver ninguém autenticado, a resposta é sempre "não, não pode" — nunca assume
permissão por padrão ("fail-safe": quando em dúvida, negar acesso).

```java
Object principal = authentication.getPrincipal();
if (!(principal instanceof Jwt jwt)) {
    return false;
}
```
Confere se a informação de quem está logado é, de fato, um token JWT. Sem essa
checagem, tratar algo que não é um `Jwt` como se fosse um `Jwt` geraria um erro interno
feio (`ClassCastException`, um `500`) em vez de simplesmente negar o acesso com um `403`
esperado — uma verificação defensiva contra um caso "impossível na teoria, mas que pode
acontecer".

```java
String role = jwt.getClaimAsString("role");
if ("SELLER_ADMIN".equals(role)) {
    return true;
}
```
Primeira regra de negócio: se o usuário for `SELLER_ADMIN` (a empresa vendedora, dona
da plataforma), pode ver o limite de crédito de **qualquer** empresa.

```java
String companyIdClaim = jwt.getClaimAsString("company_id");
return companyIdClaim != null && companyIdClaim.equals(companyId.toString());
```
Se não for `SELLER_ADMIN`: o usuário só pode ver os dados se o `companyId` pedido na
URL for **igual** ao `company_id` gravado dentro do próprio token JWT dele — uma
empresa compradora só enxerga a si mesma.

## Por que não usar um "filtro global" do Hibernate (a alternativa descartada)

O comentário na classe explica uma decisão de design importante:

> "Um filtro global do Hibernate foi deliberadamente descartado... um filtro esquecido
> devolve dados sem escopo com 200 em silêncio... Este guard... falha alto — um 403
> comprovado por teste"

O Hibernate tem um recurso que filtra automaticamente quais linhas uma consulta pode
ver, sem código explícito em cada lugar. O problema: se alguém **esquecer** de ativar
esse filtro numa consulta nova, o sistema devolve os dados de qualquer empresa
**normalmente, com sucesso (200 OK)**, sem nenhum aviso — um vazamento silencioso.

O `CompanyGuard`, ao contrário, é uma verificação **explícita**, escrita diretamente na
assinatura de cada endpoint sensível. Se alguém esquecer de colocar essa anotação num
novo endpoint, o problema fica visível: não existe controle nenhum ali, mais fácil de
pegar em revisão de código do que a ausência silenciosa de um filtro global. E quando o
guard bloqueia, ele falha de forma clara e testável — um `403` que aparece nos testes
automatizados, em vez de um vazamento que passa despercebido.
