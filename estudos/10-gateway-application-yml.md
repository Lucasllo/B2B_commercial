# application.yml do gateway

Arquivo: `gateway/src/main/resources/application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: gateway
  cloud:
    gateway:
      server:
        webmvc:
          routes:
            - id: auth-service-route
              uri: http://auth-service:8081
              predicates:
                - Path=/api/auth/**,/api/companies/**
              filters:
                - StripPrefix=1
            - id: catalog-service-route
              uri: http://catalog-service:8082
              predicates:
                - Path=/api/products/**
              filters:
                - StripPrefix=1
            - id: inventory-service-route
              uri: http://inventory-service:8083
              predicates:
                - Path=/api/inventory/**
              filters:
                - StripPrefix=1

management:
  endpoints:
    web:
      exposure:
        include: health
```

## `server.port: 8080`

Define em qual porta a aplicação escuta requisições HTTP dentro do container. É a mesma
porta exposta no `docker-compose.yml` (`ports: "8080:8080"`, ver
[01-docker-compose.md](01-docker-compose.md)) — por isso o `gateway` é o único serviço
acessível de fora sem `127.0.0.1` na frente: ele é o ponto de entrada público do
sistema.

## `spring.application.name: gateway`

O "nome de identificação" dessa aplicação dentro do ecossistema Spring — usado em logs,
métricas, e por ferramentas de observabilidade para saber qual serviço gerou aquela
informação. Não afeta o comportamento funcional.

## Nenhuma configuração de segurança neste arquivo (decisão D-05)

O `gateway` **não** valida tokens JWT. Ele só encaminha (roteia) requisições para o
serviço certo — quem valida o token é cada serviço de destino (`auth-service`, e
futuramente catalog/inventory/order), de forma independente. Conecta diretamente com
[06-jwks.md](06-jwks.md): cada serviço baixa a chave pública via
`/.well-known/jwks.json` e valida localmente, sem depender do gateway para isso.

## `spring.cloud.gateway.server.webmvc.routes` — a lista de rotas

A configuração central do gateway: uma lista de regras dizendo "se a requisição parecer
com X, mande para o serviço Y". Cada item da lista é uma rota. Hoje existem três, uma
por serviço de negócio — a estrutura é idêntica nas três, só muda `id`, `uri` e o
caminho do `predicates`:

### `id: auth-service-route`
Nome identificador da rota, para referência interna (logs, métricas, debug). Não afeta
o funcionamento.

### `uri: http://auth-service:8081`
Para onde a requisição é encaminhada quando essa rota "casar". O endereço é
`auth-service`, não `localhost` — dentro da rede interna criada pelo Docker Compose,
cada serviço enxerga os outros pelo **nome do serviço** definido no
`docker-compose.yml` como se fosse um endereço de DNS. A porta `8081` é a porta interna
que o `auth-service` expõe. As duas rotas novas seguem o mesmo padrão:
`catalog-service-route` aponta para `http://catalog-service:8082`, e
`inventory-service-route` aponta para `http://inventory-service:8083` — cada uma com a
porta interna do respectivo serviço.

### `predicates: - Path=/api/auth/**,/api/companies/**`
Um **predicate** é a condição que decide se essa rota deve ser usada para uma
requisição específica. Aqui, a condição é baseada no caminho (`Path`) da URL: se a
requisição começar com `/api/auth/` ou `/api/companies/` (o `**` significa "qualquer
coisa depois disso"), essa rota é acionada. Ex: `/api/auth/login` bate nesse predicate.
As rotas novas usam o mesmo mecanismo com prefixos diferentes:
`Path=/api/products/**` para o catálogo e `Path=/api/inventory/**` para o estoque — cada
predicate é específico o bastante para nunca colidir com as outras duas rotas.

### `filters: - StripPrefix=1`
Um **filter** modifica a requisição antes (ou depois) de ela ser encaminhada.
`StripPrefix=1` remove o primeiro segmento do caminho da URL antes de repassar para o
serviço de destino. As três rotas usam o mesmo filtro.

Na prática: uma requisição que chega no gateway como `/api/auth/login` é reescrita para
`/auth/login` antes de ser enviada ao `auth-service` — porque o `auth-service` não
conhece o prefixo `/api`, ele só expõe rotas como `/auth/login` diretamente (como visto
no `SecurityConfig.java`, que libera exatamente `/auth/login`, sem `/api` na frente). O
mesmo vale para as rotas novas: `/api/products` vira `/products` no `catalog-service`, e
`/api/inventory/{id}` vira `/inventory/{id}` no `inventory-service`. O `/api` é uma
convenção só do lado de fora, para deixar claro para quem consome a API que aquilo é um
endpoint de backend; o gateway "descasca" esse prefixo antes de repassar.

## `management.endpoints.web.exposure.include: health`

Controla quais endpoints do **Actuator** (biblioteca do Spring Boot que expõe
informações operacionais da aplicação) ficam acessíveis via HTTP. Aqui, só o `health`
está liberado — é o endpoint (`/actuator/health`) que o `HEALTHCHECK` do
`docker-compose.yml` consulta para saber se o `gateway` está de pé.

Por padrão, o Actuator expõe pouquíssimos endpoints por segurança — habilitar só
`health` (em vez de `*`, que liberaria tudo, incluindo informações sensíveis como
variáveis de ambiente) é uma escolha deliberada e mínima, coerente com o princípio de
expor só o estritamente necessário.
