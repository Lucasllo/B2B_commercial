# springdoc-openapi (Swagger UI)

Arquivos:

- `OpenApiConfig.java` em cada um dos cinco serviços (`auth-service`, `catalog-service`,
  `inventory-service`, `notification-service` e `order-service`, no pacote `config/`)
- `ErrorResponse.java` no mesmo pacote `config/` de cada um dos cinco (planos 07-03, 07-07 e 07-08)
- os controllers anotados — por exemplo
  `order-service/src/main/java/com/orderflow/order/order/OrderController.java` e
  `auth-service/src/main/java/com/orderflow/auth/auth/AuthController.java`
- `auth-service/src/main/java/com/orderflow/auth/config/JwksController.java` (o `@Hidden`)
- o bloco `springdoc:` de cada `application.yml` e os quatro caminhos liberados em cada
  `SecurityConfig.java`
- `OpenApiDocsIT.java` em `src/test/java/com/orderflow/<serviço>/` de cada um dos cinco

Continua em [28-openapi-agregado-no-gateway.md](28-openapi-agregado-no-gateway.md), que mostra
como as cinco documentações viram uma página só no Gateway.

## O que é OpenAPI e o que é Swagger UI

**OpenAPI** é um formato padrão (um JSON com uma estrutura definida) para descrever uma API HTTP:
quais rotas existem, que método cada uma usa, que campos o corpo da requisição espera, que
formato a resposta tem, que erros podem acontecer. É "a planta baixa da API", legível tanto por
humano quanto por ferramenta.

**Swagger UI** é uma página HTML que lê esse JSON e desenha uma interface clicável em cima dele —
uma lista de endpoints, cada um expansível, com um botão "Try it out" que monta e envia a
requisição de verdade, sem precisar escrever `curl` à mão.

**springdoc-openapi** é a biblioteca que, dentro de uma aplicação Spring Boot, olha para os
`@RestController` já existentes e gera esse JSON automaticamente, publicando-o num endereço
(`/v3/api-docs`) e servindo a página do Swagger UI em cima dele (`/swagger-ui.html`).

## Onde a Swagger UI mora hoje

Cada serviço continua gerando o **seu** spec em `/v3/api-docs`, e continua tendo uma Swagger UI
na sua porta direta (`8081` a `8085`). Mas a página que o avaliador usa é **uma só**, no Gateway:
`http://localhost:8080/swagger-ui.html`, com um dropdown para os cinco serviços (D-87). O
Gateway busca o spec de cada serviço por `/docs/<svc>/v3/api-docs` — os detalhes estão em
[28-openapi-agregado-no-gateway.md](28-openapi-agregado-no-gateway.md).

Esta nota fica com a parte de **dentro** de cada serviço: o que vai no spec e como o projeto
garante que ele não minta.

## O `server` `/api`: por que o "Try it out" passa pelo Gateway (D-90)

O Gateway aplica `StripPrefix=1` nas rotas: `POST /api/orders` chega ao order-service como
`POST /orders`. Por isso, dentro do spec, os caminhos aparecem **sem** `/api` — o controller só
conhece `/orders`.

Se o spec parasse aí, o "Try it out" mandaria `POST /orders` para o Gateway, que não tem essa
rota. A solução é declarar um **server**: um endereço-base que a UI cola na frente de cada
caminho. Pense no CEP da rua: o caminho é o número da casa, o server é a rua. `/api` + `/orders`
vira `/api/orders`, o caminho público.

```java
@Bean
public OpenAPI openApi(
        @Value("${orderflow.openapi.title}") String title,
        @Value("${orderflow.openapi.description}") String description,
        @Value("${orderflow.openapi.server-url:/api}") String serverUrl) {
    return new OpenAPI()
            .info(new Info().title(title).description(description).version("1.0.0"))
            .servers(List.of(new Server().url(serverUrl).description("API Gateway")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
            .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")));
}
```

O valor é **relativo** (`/api`, e não `http://localhost:8080/api`). Um endereço absoluto faria a
UI aberta por `127.0.0.1` chamar `localhost` — para o navegador são origens diferentes, e a
chamada esbarraria no CORS. Relativo, ele vale para quem abriu a página, venha de onde vier. O
valor pode ser trocado pela propriedade `orderflow.openapi.server-url` (variável de ambiente
`ORDERFLOW_OPENAPI_SERVER_URL`).

O preço: o "Try it out" pela porta direta do serviço deixa de funcionar (ele também vai tentar
`/api/...` naquela porta). É de propósito. A UI canônica é a do Gateway, e a chamada que sai dela
segue o caminho de produção — passando inclusive pelo filtro que gera o Correlation-ID (ver
[27-correlation-id-e-mdc.md](27-correlation-id-e-mdc.md)).

## Título por serviço e o botão "Authorize"

Só a dependência do springdoc já daria uma Swagger UI funcionando. O `OpenApiConfig` existe por
três coisas que a dependência sozinha não resolve:

**1. Título e descrição por serviço.** `@Value("${orderflow.openapi.title}")` lê a propriedade
do `application.yml` daquele serviço: `"OrderFlow — Auth Service API"`,
`"OrderFlow — Catalog Service API"`, `"OrderFlow — Inventory Service API"`,
`"OrderFlow — Notification Service API"`, `"OrderFlow — Order Service API"`. No dropdown do
Gateway é isso que diferencia um spec do outro.

Por que via `@Value`, e não uma propriedade `springdoc.info.title`? Porque **essa propriedade não
existe**: o springdoc só expõe `springdoc.api-docs.*` e `springdoc.swagger-ui.*` (caminhos,
ordenação, cache), nunca `info.*`, `servers` nem `securitySchemes`. Isso só entra por um bean
`OpenAPI` escrito em Java.

**2. O server `/api`**, explicado acima.

**3. O botão "Authorize" e o cadeado nos endpoints (D-89).** O bloco:

```java
.addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
.components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
```

diz duas coisas à Swagger UI: "existe um esquema de autenticação chamado `bearerAuth`, do tipo
Bearer JWT" (o `.components(...)`), e "todo endpoint deste serviço exige esse esquema, a menos
que diga o contrário" (o `.addSecurityItem(...)`, global). Sem ele, todo "Try it out" num endpoint
protegido voltaria `401`. Com ele, o avaliador faz login, clica em Authorize, cola só o token (sem
a palavra "Bearer" — a UI acrescenta) e a partir daí toda chamada sai com
`Authorization: Bearer <token>`.

## "Contratos + erros": o nível de detalhe escolhido (D-88)

Sem nenhuma anotação, o springdoc já lista rotas e corpos. Mas o resultado é pobre: nenhuma frase
dizendo **quem** pode chamar, nenhum exemplo de corpo, e só a resposta de sucesso. O avaliador não
descobre pela página que `POST /orders` pode voltar `422` ou `503`.

O nível combinado na Fase 7 se chama **"contratos + erros"**:

- **contrato** — `@Tag` agrupando as operações, `@Operation` com resumo e descrição (papel exigido,
  regra de negócio), `@Schema` com descrição e **exemplo** em cada campo dos DTOs;
- **erros** — cada resposta 4xx/5xx que o serviço **realmente** devolve declarada com
  `@ApiResponse`, com o código de erro estável na descrição e o corpo apontando para
  `ErrorResponse`.

É como a bula de um remédio: não basta dizer para que serve, tem de listar as contraindicações.

### Como as anotações aparecem no código

No controller, a `@Tag` fica na classe e cada método ganha `@Operation` e `@ApiResponses`. Trecho
real de `OrderController`:

```java
@PostMapping
@PreAuthorize("hasRole('BUYER')")
@Operation(summary = "Criar pedido",
        description = "Exige o papel BUYER; a empresa vem do claim company_id do JWT, nunca do corpo. ...")
@ApiResponses({
        @ApiResponse(responseCode = "201", description = "Pedido criado (RESERVING ou PENDING_APPROVAL); ...",
                content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Corpo inválido (validation_failed, ...) ou malformado (malformed_request)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        ...
        @ApiResponse(responseCode = "422", description = "Item inexistente ou indisponível (invalid_order_items, com productIds) ...",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Catálogo (catalog_service_unavailable) ou auth-service (auth_service_unavailable) indisponível; ...",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
```

Repare no padrão da descrição: texto curto + o código estável entre parênteses
(`invalid_order_items`, `catalog_service_unavailable`). É o mesmo código que o
`GlobalExceptionHandler` põe na chave `error` (ver
[09-global-exception-handler.md](09-global-exception-handler.md)), então quem lê a documentação
sabe exatamente o que procurar na resposta.

Nos DTOs, cada campo ganha `@Schema` com descrição e exemplo. Trecho de `OrderItemRequest`:

```java
@Schema(description = "Item do pedido: produto do catálogo e quantidade.")
public record OrderItemRequest(
        @Schema(description = "Identificador do produto no catálogo", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        @NotNull UUID productId,
        @Schema(description = "Quantidade pedida, de 1 a 1.000.000", example = "10")
        @NotNull @Positive @Max(MAX_QUANTITY_PER_ITEM) Integer quantity) {
```

O exemplo é o que a Swagger UI pré-preenche no "Try it out". Um bom exemplo poupa o avaliador de
adivinhar o formato.

`@Parameter` documenta parâmetros de rota e de query. Em `GET /orders`, o `Pageable` do Spring
vira três parâmetros explícitos (`page`, `size`, `sort`) e o objeto original fica escondido com
`@Parameter(hidden = true)` — senão o spec mostraria um objeto estranho no lugar de três campos
simples.

### O enum `OrderStatus` com os valores reais

No `OrderResponse` o campo `status` é uma `String`. Sozinho, o spec diria só "texto". O truque é:

```java
@Schema(description = "Status do pedido. O campo é texto; os valores válidos são os de OrderStatus. ...",
        example = "RESERVING", implementation = OrderStatus.class)
String status,
```

`implementation = OrderStatus.class` faz o spec listar os valores do enum Java (`CREATED`,
`PENDING_APPROVAL`, ..., `DELIVERED`) sem mudar o tipo do DTO. A descrição ainda resume as
transições permitidas.

## `ErrorResponse`: um record só para a documentação

O `GlobalExceptionHandler` de cada serviço devolve um `Map` (ver
[09-global-exception-handler.md](09-global-exception-handler.md)). Um `Map` não tem forma fixa, e o
springdoc não teria como desenhar "um objeto com `error` e `message`". Para a documentação
existir, cada serviço ganhou um `ErrorResponse`:

```java
/**
 * Schema só de documentação do corpo de erro. O {@link GlobalExceptionHandler} continua
 * devolvendo {@code Map}; este record não participa do runtime.
 */
@Schema(description = "Corpo de erro uniforme. A chave fields aparece só quando error é validation_failed; "
        + "a chave productIds aparece só quando error é invalid_order_items.")
public record ErrorResponse(
        @Schema(description = "Código estável do erro", example = "invalid_order_items")
        String error,
        ...
```

É uma **maquete** do corpo de erro: ninguém mora nela, ela só mostra como a casa é. O handler
**não** foi alterado — trocar o `Map` pelo record mudaria o comportamento real do serviço só para
agradar a documentação, e esse risco não compensava.

Cada serviço tem o seu, com os campos que o seu handler devolve de verdade:

| Serviço | Campos do `ErrorResponse` |
|---|---|
| order-service | `error`, `message`, `fields`, `productIds` (só em `invalid_order_items`) |
| inventory-service | `error`, `message`, `fields`, `available` e `requested` (só em `insufficient_stock`) |
| notification-service | só `error` e `message` (o serviço não tem `validation_failed`) |
| auth-service, catalog-service | `error`, `message`, `fields` |

A regra geral (07-08): a documentação só descreve o que o serviço devolve **de fato**. Exemplo:
`GET /inventory/{productId}` não documenta `400` para UUID inválido, porque o inventory não tem
tratamento para esse caso — documentar seria prometer algo que não existe.

## O que fica de fora do spec, e o que fica público

**`POST /auth/login` é a única operação pública.** Como o `bearerAuth` é global, o login também
apareceria com cadeado — e o avaliador ficaria preso: para pegar o token ele precisaria de um token.
A anotação `@SecurityRequirements`, **vazia**, apaga a exigência só nessa operação:

```java
// Login é a única operação pública: é onde o avaliador obtém o token para o botão Authorize (D-89).
@SecurityRequirements
@PostMapping("/auth/login")
@Operation(summary = "Entrar", description = "Operação pública. Não envie bearerAuth; use a resposta para preencher o Authorize.")
```

No JSON isso vira `"security": []` na operação. O exemplo do `LoginRequest` é o usuário de
demonstração publicado no README (`admin@orderflow.local`), então o "Try it out" do login funciona
sem editar nada.

**O JWKS fica fora do spec.** O `/.well-known/jwks.json` existe para os resource servers validarem
o JWT (ver [06-jwks.md](06-jwks.md)), mas o Gateway não roteia `/.well-known/**`. Listá-lo faria o
"Try it out" responder `404`. Por isso a classe leva `@Hidden`:

```java
@Hidden
@RestController
public class JwksController {
```

`@Hidden` tira o endpoint do spec, não do serviço: ele continua respondendo normalmente na rede
interna do compose.

**O Actuator também fica fora**, pelo `show-actuator: false` do `application.yml`.

## O bloco `springdoc:` do `application.yml`

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: method
    tags-sorter: alpha
  show-actuator: false
```

`api-docs.path` e `swagger-ui.path` são os valores **padrão** da biblioteca, declarados mesmo
assim para ficarem visíveis ao lado dos caminhos liberados no `SecurityConfig`. `operations-sorter`
e `tags-sorter` só organizam a página. `show-actuator: false` (também o padrão) garante que
`/actuator/health` e parentes não apareçam no spec público.

## Por que o `SecurityConfig` libera exatamente quatro caminhos (D-91)

```java
.requestMatchers(..., "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**")
        .permitAll()
```

Sem isso, a documentação cairia no `.anyRequest().authenticated()` visto em
[05-security-config.md](05-security-config.md) — e um usuário sem token não conseguiria nem abrir
a página que ensina a fazer login. Os quatro caminhos cobrem exatamente o que o springdoc serve:

- `/swagger-ui.html` — a URL que se digita; na prática, um redirecionamento para
  `/swagger-ui/index.html`.
- `/swagger-ui/**` — os arquivos estáticos da página (HTML, CSS, JavaScript).
- `/v3/api-docs` — o JSON do spec.
- `/v3/api-docs/**` — inclui `/v3/api-docs/swagger-config`, que a UI busca ao carregar.

Um padrão mais largo (`/v3/**` ou `/**`) abriria qualquer rota futura com esse prefixo. Por isso os
quatro caminhos são listados literalmente. O "Try it out" **não** precisou mexer no
`SecurityConfig`: o token vai no header, e cada serviço o valida como sempre.

O spec revela o **formato** da API — rotas, campos, papéis exigidos —, nunca dado de negócio nem
segredo. As portas diretas (`8081` a `8085`) ficam presas a `127.0.0.1` no `docker-compose.yml`.
O risco é aceito para um projeto de portfólio, com a ressalva registrada no `SecurityConfig` e no
README de que a liberação deveria ser fechada num profile de produção real.

## `OpenApiDocsIT`: o teste que lê o contrato inteiro

Anotação é texto, e texto envelhece: alguém cria um endpoint novo, esquece o `@ApiResponse`, e a
documentação passa a mentir sem ninguém perceber. O `OpenApiDocsIT` de cada serviço sobe a
aplicação (Testcontainers), pede `GET /v3/api-docs` e **percorre o JSON**.

O que ele cobra, com nomes reais dos métodos de teste do order-service:

- `specJsonIsAccessibleWithoutTokenAndDeclaresBearerAuth` — o spec abre sem token, com o título
  certo e o `bearerAuth` declarado;
- `swaggerConfigUnderApiDocsIsAccessibleWithoutToken` e `swaggerUiHtmlLetsSecurityChainPassWithoutToken`
  — os caminhos do springdoc estão liberados;
- `businessEndpointStillRequiresTokenGuardAgainstRegression` — `GET /orders` sem token continua
  `401`, ou seja, liberar a documentação não abriu o resto;
- `serverUrlIsApiPrefix` — `servers[0].url` é `/api`;
- `specHasExactlySevenOperationsEachWithSummaryTagAndErrors` — conta as operações (sete no
  order-service) e exige, em **cada uma**, `summary`, pelo menos uma tag, resposta `401` e, em
  todo 4xx/5xx, `$ref` apontando para `ErrorResponse`;
- `orderStatusEnumInBothResponsesMatchesOrderStatusValues` — o enum do spec, em `OrderResponse` e
  `OrderSummaryResponse`, é igual a `OrderStatus.values()`, na mesma ordem. Um status novo no
  Java sem reflexo no spec quebra o build;
- `createListAndGetOperationsDocumentRealErrors` e `decisionAndShipmentOperationsDocumentRealErrors`
  — códigos específicos, como `422` com `invalid_order_items` e `409` com `order_not_pending` ou
  `invalid_order_transition`.

No auth-service, `serverUrlIsApiPrefixAndJwksIsHidden` confere que `/.well-known/jwks.json` **não**
está em `paths`, e `loginIsPublicAndOtherOperationsInheritBearerAuth` confere que o login tem
`security` vazio e que nenhuma outra operação sobrescreve a segurança global.

O detalhe importante é o "percorre": o teste não lista endpoint por endpoint, ele passa por
**todos** os `paths` do JSON. Um endpoint novo entra na checagem sozinho, sem ninguém lembrar de
atualizar o teste. Mais sobre os testes do projeto em [15-testes.md](15-testes.md).
