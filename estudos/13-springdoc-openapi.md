# springdoc-openapi (Swagger UI)

Arquivos: `OpenApiConfig.java` em cada um dos cinco serviços (`auth-service`, `catalog-service`,
`inventory-service`, `notification-service` e `order-service`, dentro do respectivo pacote
`config/`), mais o bloco `springdoc:` de cada `application.yml` e os quatro caminhos liberados em
cada um dos cinco `SecurityConfig.java`.

## O que é OpenAPI e o que é Swagger UI

**OpenAPI** é um formato padrão (um JSON com uma estrutura definida) para descrever uma API HTTP:
quais rotas existem, que método cada uma usa, que campos o corpo da requisição espera, que
formato a resposta tem, que erros podem acontecer. É "a planta baixa da API", legível tanto por
humano quanto por ferramenta.

**Swagger UI** é uma página HTML que lê esse JSON e desenha uma interface clicável em cima dele —
uma lista de endpoints, cada um expansível, com um botão "Try it out" que monta e envia a
requisição de verdade, sem precisar escrever `curl` à mão.

**springdoc-openapi** é a biblioteca que, dentro de uma aplicação Spring Boot, olha para os
`@RestController` já existentes (sem precisar de nenhuma anotação extra na maioria dos casos) e
gera esse JSON automaticamente, publicando-o num endereço (`/v3/api-docs`) e servindo a página do
Swagger UI em cima dele (`/swagger-ui.html`).

## Por que cada serviço tem sua própria Swagger UI, e não uma só no Gateway

A Swagger UI de cada serviço roda na **porta direta** dele — `8081` (auth-service), `8082`
(catalog-service), `8083` (inventory-service), `8084` (notification-service), `8085`
(order-service) — nunca pelo `gateway` (porta `8080`). Duas razões:

1. O `gateway` não tem nenhum `@RestController` de negócio próprio (ver
   [10-gateway-application-yml.md](10-gateway-application-yml.md)) — ele só roteia. Não há nada
   para o springdoc documentar ali.
2. As portas diretas dos serviços já estão expostas em `127.0.0.1` no `docker-compose.yml`
   especificamente para depuração local (ver [01-docker-compose.md](01-docker-compose.md)) — a
   Swagger UI é mais uma ferramenta de depuração local, então faz sentido morar no mesmo lugar.

Consequência prática: como o `gateway` aplica `StripPrefix=1` nas suas rotas (remove o `/api` antes
de repassar), os caminhos que aparecem dentro da Swagger UI de cada serviço **não** têm o prefixo
`/api` — `POST /api/products` no Gateway aparece como `POST /products` na Swagger UI do
catalog-service. Não é inconsistência, é porque a UI está do outro lado do "descascamento" do
prefixo.

## `OpenApiConfig.java` — por que existe uma classe de configuração, e não só a dependência

```java
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi(
            @Value("${orderflow.openapi.title}") String title,
            @Value("${orderflow.openapi.description}") String description) {
        return new OpenAPI()
                .info(new Info().title(title).description(description).version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
```

Só adicionar a dependência do springdoc no `pom.xml` já seria suficiente para ter uma Swagger UI
funcionando — ela detecta os controllers sozinha. Essa classe existe para resolver dois problemas
que a dependência sozinha não resolve:

**1. Título e descrição por serviço.** Sem essa classe, as cinco Swagger UIs teriam o mesmo título
genérico, e um desenvolvedor com cinco abas abertas não saberia qual é qual. `@Value("${orderflow.openapi.title}")`
lê a propriedade do `application.yml` daquele serviço específico — é por isso que o título é
`"OrderFlow — Catalog Service API"` numa aba, `"OrderFlow — Inventory Service API"` na outra, e
assim por diante (`"OrderFlow — Auth Service API"`, `"OrderFlow — Notification Service API"`,
`"OrderFlow — Order Service API"`), mesmo a classe Java sendo estruturalmente idêntica nos cinco.

Por que via `@Value` num parâmetro de método, e não uma propriedade `springdoc.info.title` no
`application.yml`? Porque **essa propriedade não existe** — foi um erro comum de suposição descoberto
durante o planejamento desta tarefa: o springdoc só expõe `springdoc.api-docs.*` e
`springdoc.swagger-ui.*` (caminhos, ordenação, cache), nunca `info.*` nem `securitySchemes`. Título e
esquema de segurança só podem entrar via um bean `OpenAPI` escrito em Java, como acima.

**2. O botão "Authorize" e o cadeado nos endpoints.** Sem o `SecurityScheme`/`SecurityRequirement`
declarados aqui, a Swagger UI ainda abriria normalmente — mas não teria o botão "Authorize", e todo
"Try it out" num endpoint protegido devolveria `401`, porque a requisição sairia sem o header
`Authorization`. O bloco:

```java
.addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
.components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
```

diz duas coisas ao Swagger UI: "existe um esquema de autenticação chamado `bearerAuth`, do tipo
Bearer JWT" (a parte `.components(...)`), e "todo endpoint deste serviço exige esse esquema, a
menos que diga o contrário" (a parte `.addSecurityItem(...)`, aplicada globalmente). Isso é o que
faz o botão Authorize aparecer no topo da página — clicar nele, colar só o valor do token (sem a
palavra "Bearer" na frente, a UI já sabe acrescentar por causa do `bearerFormat: JWT`), e a partir
daí todo "Try it out" já sai com o header `Authorization: Bearer <token>` preenchido sozinho.

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

`api-docs.path` e `swagger-ui.path` aqui são, na verdade, os valores **padrão** da biblioteca —
declarados explicitamente mesmo assim, para que exista uma única fonte de verdade legível junto da
lista de caminhos liberados no `SecurityConfig` (ver abaixo), em vez de o leitor precisar saber de
cor os defaults da biblioteca. `operations-sorter`/`tags-sorter` são só organização visual da
página. `show-actuator: false` (também o default) é o mais importante dos quatro: garante que os
endpoints do Actuator (informação operacional interna, como `/actuator/health`) **não** apareçam
no spec público — mantém o documento restrito às rotas de negócio do serviço.

## Por que o `SecurityConfig` precisou liberar exatamente quatro caminhos, nem mais nem menos

```java
.requestMatchers(..., "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**")
        .permitAll()
```

A Swagger UI, como qualquer outra rota do serviço, cairia no `.anyRequest().authenticated()` visto
em [05-security-config.md](05-security-config.md) se nada fosse liberado — e um usuário sem token
não consegue nem abrir a documentação para descobrir como logar (o mesmo paradoxo já visto para o
JWKS em [06-jwks.md](06-jwks.md)). Os quatro caminhos cobrem exatamente o que o springdoc serve:

- `/swagger-ui.html` — a URL que o desenvolvedor digita; na prática, um redirecionamento para
  `/swagger-ui/index.html`.
- `/swagger-ui/**` — os arquivos estáticos da página (HTML, CSS, JavaScript) que fazem a interface
  funcionar.
- `/v3/api-docs` — o JSON do spec OpenAPI em si, que a página busca para saber o que desenhar.
- `/v3/api-docs/**` — inclui `/v3/api-docs/swagger-config`, um segundo arquivo de configuração que
  a UI busca ao carregar.

Liberar um padrão mais largo (por exemplo `/v3/**` ou `/**`) teria sido mais fácil de escrever, mas
teria aberto qualquer rota futura que por acaso comece com esse prefixo — o tipo de erro que a
revisão de código deste projeto trata como achado de severidade alta (ver
`GlobalExceptionHandler`/[09-global-exception-handler.md](09-global-exception-handler.md) pelo
mesmo princípio de nunca ser genérico demais numa regra de segurança). Por isso os quatro caminhos
são listados literalmente, e `.anyRequest().authenticated()` continua sendo a regra padrão para
tudo o mais.

## Por que essa superfície sem autenticação é aceitável aqui

O spec OpenAPI revela o **formato** da API — nomes de rota, campos esperados, papéis exigidos —
nunca dado de negócio real nem segredo algum. Combinado com o fato de que as portas diretas
(`8081` a `8085`) já estão restritas a `127.0.0.1` no `docker-compose.yml` (não alcançáveis de
fora da máquina, e não roteadas pelo `gateway`), o risco é tratado como aceitável para um projeto de
portfólio sem ambiente de produção — com a condição explícita, registrada tanto no comentário do
`SecurityConfig` quanto no README, de que essa liberação deveria ser fechada se algum dia existir
um profile de produção real.
