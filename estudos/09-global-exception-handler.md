# GlobalExceptionHandler

Arquivo: `auth-service/src/main/java/com/orderflow/auth/config/GlobalExceptionHandler.java`

## O que essa classe faz, em uma frase

É o "tradutor de erros" do `auth-service`: sempre que algo dá errado em qualquer parte
da aplicação, essa classe intercepta o problema e transforma numa resposta HTTP
padronizada e segura, em vez de deixar o erro "vazar" de forma bagunçada ou perigosa
para quem fez a requisição.

## O que é uma exceção (`Exception`)

Quando um programa encontra um problema que não sabe resolver ali mesmo — por exemplo,
tentar cadastrar um email que já existe, ou buscar uma empresa que não existe no banco —
ele "lança" (`throw`) uma **exceção**. É como um alarme: o código para o que estava
fazendo e "grita" que algo deu errado, e esse aviso vai subindo pelas camadas do
programa até alguém decidir o que fazer com ele.

Se **ninguém** capturar esse aviso, o programa quebra feio: numa API web, isso
normalmente significa que o cliente recebe um erro genérico e feio (um "Erro Interno
500" cheio de detalhes técnicos internos que não deveriam aparecer para quem está de
fora).

## O problema que essa classe resolve

Sem uma classe como essa, cada tipo diferente de erro no `auth-service` poderia gerar
uma resposta completamente diferente e imprevisível — cada `Controller` teria que
lembrar de tratar cada erro manualmente, e provavelmente esqueceria de alguns, ou
trataria de formas inconsistentes entre si.

O comentário no topo da classe deixa isso claro: "Corpo de erro uniforme para todo o
serviço — sempre as chaves `error` e `message`". Não importa qual erro aconteça em
qualquer lugar da aplicação, a resposta sempre tem o mesmo formato, algo como:

```json
{
  "error": "email_already_used",
  "message": "Email is already in use"
}
```

Isso é bom tanto para quem consome a API (sabe sempre onde procurar o erro) quanto para
quem mantém o código (não precisa reinventar o tratamento de erro em cada `Controller`).

## `@RestControllerAdvice` — o "escutador global"

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
```

Diz ao Spring: "essa classe fica de olho em **toda a aplicação**, não só num
`Controller` específico". Funciona como uma rede de segurança por cima de todos os
endpoints do `auth-service` — se qualquer um deles lançar uma exceção, essa classe entra
em ação automaticamente, sem que o `Controller` precise chamar nada explicitamente.

## `@ExceptionHandler` — "se acontecer isso, faça aquilo"

Cada método marcado com `@ExceptionHandler(TipoDoErro.class)` é uma regra: "se esse tipo
específico de exceção acontecer em qualquer lugar da aplicação, rode este método para
decidir o que responder".

- **`MethodArgumentNotValidException`** — lançada quando dados enviados numa requisição
  falham validação (`spring-boot-starter-validation`). Monta a lista de campos
  inválidos (`fields`) e devolve `400 Bad Request`.
- **`EmailAlreadyUsedException` / `DataIntegrityViolationException`** — um método pode
  tratar mais de um tipo de exceção ao mesmo tempo, se a resposta desejada for igual
  para ambas. Tanto uma verificação de negócio explícita quanto um erro vindo direto do
  banco (constraint `UNIQUE`, ver [08-flyway-migrations.md](08-flyway-migrations.md))
  resultam na mesma resposta amigável: `409 Conflict`, sem expor o erro técnico bruto do
  banco.
- **`CompanyNotFoundException`** — `404 Not Found` com mensagem genérica.
- **`AccessDeniedException`** — `403 Forbidden`, usuário autenticado mas sem permissão
  (conecta com `@PreAuthorize` e o `SecurityConfig`, ver
  [05-security-config.md](05-security-config.md)).
- **`AuthenticationException`** — `401 Unauthorized`. O `401` de uma requisição **sem
  token nenhum** nunca passa por essa classe — é respondido antes, direto pelo filtro de
  segurança do Spring. Esse handler só cobre `AuthenticationException` lançada *durante*
  o processamento normal da requisição.

## Por que isso é uma questão de segurança, não só organização

O comentário no topo é explícito: "Nenhum handler inclui stack trace, nome de classe de
exceção, fragmento de SQL ou qualquer valor de campo de senha no corpo da resposta".

Sem esse cuidado, um erro de banco mal tratado poderia, sem querer, revelar detalhes
internos da aplicação — nome de tabela, estrutura de query, ou até um dado sensível. Esse
tipo de vazamento é conhecido como "information disclosure" e pode ajudar um atacante a
entender como o sistema funciona por dentro. Por isso, cada `@ExceptionHandler` aqui
devolve **sempre** uma mensagem curta e pré-definida (`errorBody(...)`), nunca o
conteúdo cru da exceção original.

## O método auxiliar `errorBody(...)`

```java
private Map<String, Object> errorBody(String error, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", error);
    body.put("message", message);
    return body;
}
```

Um pequeno atalho para não repetir a mesma criação de `Map` em cada método — centraliza
a montagem do formato padrão (`error` + `message`) num único lugar, reforçando a
consistência que essa classe existe para garantir.

## E nos outros serviços?

Tudo acima descreve o `auth-service`, mas **cada serviço tem o seu próprio**
`config/GlobalExceptionHandler.java` (catalog, inventory, notification e order). Todos
seguem a mesma receita — `@RestControllerAdvice`, o mesmo formato de corpo (`error` +
`message`, com o mesmo método `errorBody(...)`) e a mesma regra de nunca vazar detalhe
técnico. O que muda são os **mapeamentos**: cada serviço traduz as exceções do seu próprio
domínio. (Os handlers de `403 forbidden` e `401 unauthorized` se repetem em todos.)

| Serviço | Exceção | Resposta |
| --- | --- | --- |
| catalog | `MethodArgumentNotValidException` | `400 validation_failed` (com `fields`) |
| catalog | `HttpMessageNotReadableException` (corpo malformado, ex.: status desconhecido no enum) | `400 malformed_request` |
| catalog | `SkuAlreadyUsedException` / `DataIntegrityViolationException` | `409 sku_already_used` |
| catalog | `ProductNotFoundException` | `404 product_not_found` |
| inventory | `MethodArgumentNotValidException` | `400 validation_failed` (com `fields`) |
| inventory | `HttpMessageNotReadableException` | `400 malformed_request` |
| inventory | `InventoryNotFoundException` | `404 inventory_not_found` |
| inventory | `InsufficientStockException` | `409 insufficient_stock` (com `available` e `requested`) |
| inventory | `StockBelowReservedException` | `409 stock_below_reserved` |
| inventory | `ReservationConflictException` | `503 reservation_conflict` |
| inventory | `DataIntegrityViolationException` | `409 data_conflict` |
| notification | `MethodArgumentTypeMismatchException` (id que não é UUID) | `400 invalid_identifier` |
| notification | `SdkException` (falha do SDK da AWS ao falar com o DynamoDB) | `503 notification_store_unavailable` — o motivo real vai só para o log do servidor |
| order | `MethodArgumentNotValidException` | `400 validation_failed` (com `fields`) |
| order | `HttpMessageNotReadableException` | `400 malformed_request` |
| order | `MethodArgumentTypeMismatchException` (ex.: `orderId` que não é UUID) | `400 invalid_parameter` |
| order | `DuplicateOrderItemsException` | `400 validation_failed` com `fields.items` |
| order | `OrderNotFoundException` | `404 order_not_found` |
| order | `OrderNotPendingException` | `409 order_not_pending` |
| order | `InvalidOrderItemsException` | `422 invalid_order_items` (com `productIds`) |
| order | `OrderTotalOutOfRangeException` | `422 order_total_out_of_range` |
| order | `CatalogServiceUnavailableException` / `AuthServiceUnavailableException` | `503 catalog_service_unavailable` / `503 auth_service_unavailable` |

Dois detalhes que valem notar:

- No `inventory-service`, `insufficient_stock` (409) e `reservation_conflict` (503) são
  **de propósito** códigos diferentes: falta de estoque de verdade é uma recusa definitiva;
  disputa entre requisições simultâneas que esgotou as tentativas é passageira e vale a pena
  tentar de novo (ver [14-spring-retry.md](14-spring-retry.md)).
- No `order-service`, o produto repetido usa o **mesmo envelope** `validation_failed` da
  validação normal, para o cliente só precisar tratar um formato de erro 400. O contexto de
  cada erro do pedido está em [22-services-de-pedido.md](22-services-de-pedido.md).
