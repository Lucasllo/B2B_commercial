# OpenAPI agregado no Gateway

Arquivos:

- `gateway/src/main/resources/application.yml` — rotas `/docs/<svc>/v3/api-docs` e o bloco
  `springdoc.swagger-ui.urls`
- `gateway/pom.xml` — `springdoc-openapi-starter-webmvc-ui` (só a UI; o Gateway não documenta
  endpoint próprio)

Continuação de [13-springdoc-openapi.md](13-springdoc-openapi.md), que explica o spec de cada
serviço na porta direta dele, e de [10-gateway-application-yml.md](10-gateway-application-yml.md),
que explica o roteamento. A UI canônica deixa de ser "uma página por porta" e passa a ser uma
página só no Gateway.

## O que cada serviço publica

Cada serviço já gera o seu **spec OpenAPI**: um JSON em `/v3/api-docs` que descreve os
endpoints, os corpos e os erros. A **Swagger UI** é a página que lê esse JSON e desenha a
lista clicável, com o "Try it out".

Até aqui essa página morava na porta direta de cada serviço (`8081`–`8085`). Funciona para
quem está depurando um serviço isolado. Para quem avalia o sistema inteiro, são cinco URLs,
cinco portas e cinco origens diferentes.

## Por que uma UI só, no Gateway (D-87)

O avaliador abre **uma** URL: `http://localhost:8080/swagger-ui.html`. O dropdown dessa página
lista os cinco serviços (`auth-service`, `catalog-service`, `inventory-service`,
`notification-service`, `order-service`) e abre em `order-service`, que é o fluxo central.

O Gateway não tem API de negócio para documentar. O spec que o próprio springdoc geraria em
`/v3/api-docs` do Gateway existe, mas **não entra no dropdown**. O que a UI mostra são os
specs dos outros cinco.

## Como o dropdown busca o spec sem CORS

A página e o JSON precisam vir da **mesma origem** (mesmo host e mesma porta). Se a UI em
`localhost:8080` pedisse o JSON em `localhost:8082`, o navegador bloquearia (CORS).

Por isso cada item do dropdown aponta para um caminho **relativo** no próprio Gateway:

| Serviço | URL no dropdown | Para onde o Gateway manda |
|---|---|---|
| auth-service | `/docs/auth/v3/api-docs` | auth, caminho reescrito para `/v3/api-docs` |
| catalog-service | `/docs/catalog/v3/api-docs` | catalog, idem |
| inventory-service | `/docs/inventory/v3/api-docs` | inventory, idem |
| notification-service | `/docs/notification/v3/api-docs` | notification, idem |
| order-service | `/docs/order/v3/api-docs` | order, idem |

O filtro da rota é `SetPath=/v3/api-docs`. O serviço continua publicando o spec no caminho
que ele já tinha. O Gateway só traduz o endereço público.

## Por que não é `/api/<svc>/v3/api-docs`

As rotas de negócio já ocupam `/api/products/**`, `/api/inventory/**` e os outros prefixos
`/api/...`. Um caminho de documentação embaixo de `/api/<svc>/...` cairia na rota de negócio
errada — `/api/products` não tem um segmento "catalog" para distinguir "isto é o spec" de
"isto é um produto". O prefixo `/docs/` não colide com nenhuma delas. A URL que o avaliador
digita continua sendo uma só; o que muda é o miolo do dropdown, e isso o D-87 deixa a cargo
de quem organiza as rotas.

As rotas de documentação ficam **antes** das rotas de negócio na lista, e o predicado é só
`Path` — sem método. Um predicado que cobre todo método HTTP já derrubou a subida do
springdoc em outra versão (`Unexpected value: TRACE`); com `Path` só, não acontece.

## Por que o "Try it out" passa pelo Gateway (D-90)

O botão "Try it out" usa o `server` declarado no spec. Esse `server` é o caminho público
`/api`, não a porta direta do serviço. A chamada sai da página do Gateway, entra de novo no
Gateway, e segue a rota de negócio (`StripPrefix=1`) até o serviço certo. Assim o teste no
navegador exercita o mesmo caminho que um cliente real, inclusive o Correlation-ID que o
Gateway cola na ida.

Abrir a UI pela porta direta do serviço e tentar "Try it out" deixa de bater no recurso,
porque o `server` relativo `/api` não existe naquela porta. A página canônica é a do Gateway.

## O que é público e o que continua pedindo JWT (D-91)

O Gateway **não tem Spring Security** (D-05). Não há `permitAll` para configurar aqui: quem
chega no Gateway passa, e cada serviço decide. Nos serviços, continuam liberados só os
caminhos de documentação. O resto da API segue exigindo JWT. A UI aberta em `0.0.0.0:8080`
é documentação de uma demo local; não é um atalho para os endpoints de negócio.
