# Phase 3: Primeira Integração Assíncrona — Histórico de Notificações - Context

**Gathered:** 2026-09-22
**Status:** Ready for planning

<domain>
## Phase Boundary

Um evento de ciclo de vida publicado por um serviço já existente (o ajuste de estoque no inventory-service) vira um registro consultável no DynamoDB do notification-service, provando o encanamento SQS + DynamoDB via LocalStack no cenário mais simples possível — antes de a saga da Fase 5 depender dele. Nenhum pedido existe ainda nesta fase (order-service é Fase 4): o evento de prova é sobre um produto, não sobre um pedido. O notification-service é um sink puro — consome eventos, nunca é chamado de forma síncrona por outro serviço, e não faz nenhuma chamada de saída.

</domain>

<decisions>
## Implementation Decisions

### Evento-gatilho da prova de conceito
- **D-27:** O evento publicado nesta fase é o **ajuste de quantidade em estoque** (`PUT /inventory/{productId}/stock`, INV-01) — a ação de upsert já existente da Fase 2, exatamente o exemplo citado no ROADMAP.md. — **Reversibility:** reversible — adicionar publicação em outros endpoints depois é aditivo.
- **D-28:** **Reservar** e **liberar** estoque (endpoints existentes da Fase 2) **não** publicam evento nesta fase — escopo mínimo deliberado. Instrumentar esses endpoints fica mais natural na Fase 5, quando a própria reserva já estiver em jogo na saga. — **Reversibility:** reversible.

### Outbox agora ou só na Fase 5
- **D-29:** O inventory-service publica o evento de ajuste de estoque **direto no SQS**, sem tabela Transactional Outbox nesta fase. O padrão Outbox (constraint do projeto) é introduzido no order-service/inventory-service na Fase 5, quando a saga de reserva realmente precisa da garantia de atomicidade DB+evento (PITFALLS.md, Pitfall 2 — "fase onde order-service é conectado pela primeira vez ao inventory-service"). — **Reversibility:** costly — o código de publicação escrito aqui (save + send direto) precisa ser reescrito/estendido na Fase 5 para passar pela tabela outbox; não é descartável, mas não é reaproveitável como está.
- **D-30:** O risco de dual-write (Postgres commitado, SQS falha ao publicar, ou vice-versa) fica **sem mitigação técnica** nesta fase — deve ser documentado como **limitação conhecida** (ADR ou nota no README), explicando que a Fase 5 resolve isso corretamente onde a consistência realmente importa (a saga). — **Reversibility:** reversible — é uma decisão de documentação, não de código.

### Formato de consulta do histórico
- **D-31:** O endpoint de consulta devolve uma **lista de eventos por identificador da entidade** (`GET /notifications/{productId}`, DynamoDB **Query** por partition key), não um registro único por `GetItem`. Decisão pensada para a Fase 6 reaproveitar a mesma forma de consulta para a timeline completa do pedido (`GET /notifications/{orderId}`). — **Reversibility:** costly — trocar de Query-por-lista para GetItem-único depois quebraria o contrato do endpoint já em uso por quem avaliar o projeto e pela Fase 6.
- **D-32:** Partition key da tabela DynamoDB = **`productId`** nesta fase (o identificador da entidade do evento). Quando a Fase 4/6 trouxer eventos de pedido, o mesmo desenho de tabela passa a usar `orderId` como partition key — mesmo padrão de chave, entidade diferente, sem introduzir uma camada de indireção genérica agora. — **Reversibility:** reversible — é uma convenção de nomenclatura de campo, não uma mudança estrutural de schema.
- **D-33:** Sort key = **`eventType` + identificador único do evento** (gerado no momento da publicação — UUID ou timestamp), não apenas `eventType`. Isso distingue **reentrega da mesma mensagem SQS** (mesmo identificador → sobrescreve, satisfaz o Success Criteria 3 do ROADMAP) de **um novo evento distinto do mesmo tipo** na mesma entidade (identificador diferente → nova linha no histórico, não apaga o anterior). — **Reversibility:** one-way — mudar a composição da sort key depois de haver itens reais na tabela quebra a leitura de itens já gravados com o formato antigo.

### Conteúdo do registro de notificação
- **D-34:** O registro grava **payload bruto do evento** (dados estruturados originais: productId, quantidade anterior, quantidade nova, timestamp) **e** uma **mensagem legível** gerada a partir dele (ex.: "Estoque do produto X ajustado para Y unidades"). Ambos os campos — pouco custo extra, valor demonstrativo alto para quem avaliar o endpoint direto pelo Swagger. — **Reversibility:** reversible.
- **D-35:** Dois ajustes de estoque distintos no mesmo produto geram **duas linhas separadas** no histórico (consequência direta de D-33 — a sort key inclui o identificador único do evento, não só o tipo). Não existe "sobrescrever pelo mais recente" fora do caso de reentrega da mesma mensagem. — **Reversibility:** reversible (consequência de D-33, já registrada lá).

### Claude's Discretion
- Nome exato do evento/tipo (ex.: `STOCK_ADJUSTED` vs `InventoryStockAdjustedEvent`) e do payload DTO compartilhado entre inventory-service e notification-service — decisão de design de contrato de mensagem, definir no planejamento.
- Nome exato da fila SQS e da tabela DynamoDB, e como são provisionadas automaticamente na subida do LocalStack (script de init vs `awslocal` em entrypoint) — Success Criteria 4 do ROADMAP exige que isso aconteça sem passo manual; mecanismo exato fica para o planejamento.
- Uso de `dynamodb-enhanced` (`@DynamoDbBean`) vs cliente SDK direto para o repositório do notification-service — ambos atendem ao mesmo contrato de dados; escolher no planejamento com base na pesquisa da API atual (ver Blocker/Concern em STATE.md).
- Papel/JWT exigido para consultar o endpoint de histórico (SELLER_ADMIN apenas, ou qualquer usuário autenticado) — decisão de autorização de baixo risco, definir no planejamento.
- Formato exato da mensagem legível (template string vs biblioteca de formatação) — detalhe de implementação.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Escopo e critérios de aceitação da fase
- `.planning/ROADMAP.md` §Phase 3: Primeira Integração Assíncrona — Goal, Requirements (NOTF-01, NOTF-02) e os 4 Success Criteria (inclui o critério 3 sobre chave determinística `id#tipoDeEvento` e o critério 4 sobre provisionamento automático de filas/tabela).
- `.planning/REQUIREMENTS.md` §Notifications — texto completo de NOTF-01, NOTF-02.

### Contexto e restrições do projeto
- `.planning/PROJECT.md` §Constraints — SQS via LocalStack, DynamoDB via LocalStack, padrão Transactional Outbox (nota: D-29 desta fase esclarece que o outbox do inventory-service entra na Fase 5, não aqui).
- `.claude/CLAUDE.md` §Technology Stack — Spring Cloud AWS (`SqsTemplate`/`@SqsListener`), AWS SDK v2 `dynamodb-enhanced` (`@DynamoDbBean`), Testcontainers `localstack` module, Awaitility para asserções assíncronas.
- `.planning/STATE.md` §Blockers/Concerns — "Fase 3/5: verificar API atual do Spring Cloud AWS (SqsTemplate, @SqsListener) e o desenho da tabela DynamoDB durante o planejamento" (lacuna MEDIUM de confiança da pesquisa — validar no planejamento/pesquisa da fase).

### Pesquisa de arquitetura e riscos
- `.planning/research/PITFALLS.md` — Pitfall 2 (dual-write/outbox — fundamenta D-29/D-30), Pitfall 3 (consumidores idempotentes — fundamenta D-33, cita explicitamente "chave determinística `orderId#eventType`" como o mesmo padrão usado aqui com `productId`), Pitfall 5 (quirks do LocalStack SQS/DynamoDB — atenção a PartiQL e operações avançadas), Pitfall 7 (testes: priorizar Testcontainers real sobre mocks para o fluxo de mensageria).
- `.planning/research/ARCHITECTURE.md` §Service Breakdown (linha do notification-service — "purely reactive, consumes order/inventory lifecycle events, no REST writes from other services"), §Key Data Flows item 3 ("Fan-out notification path... intentionally the simplest component to build and a good 'first' async integration"), §External Integration Patterns (tabela DynamoDB — "keep the table schema... simple, partition key = orderId or notificationId").

### Decisões herdadas de fases anteriores (base assumida pronta)
- `.planning/phases/02-cat-logo-e-estoque/02-CONTEXT.md` — D-08/D-09 (semântica on_hand/reserved do inventory-service, endpoint de ajuste já existe), D-20/D-21 (retry de lock otimista no inventory-service — o evento de ajuste desta fase é publicado só após o ajuste ser bem-sucedido, depois de qualquer retry).
- `.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-CONTEXT.md` — D-01 (Postgres único, schema por serviço — não se aplica ao notification-service, que usa DynamoDB), validação de JWT local sem chamada síncrona ao auth-service (aplica-se ao endpoint de consulta do notification-service).
- `docker-compose.yml` (raiz do repo) — LocalStack já configurado com `SERVICES=sqs,dynamodb`, `LOCALSTACK_AUTH_TOKEN` obrigatório, imagem fixada (`localstack/localstack:2026.08.3`). Nenhuma dependência AWS SDK/Spring Cloud AWS existe ainda em nenhum `pom.xml` — greenfield para mensageria.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- Reactor Maven multi-módulo já configurado (`pom.xml` raiz) — `notification-service` entra como novo módulo irmão de `auth-service`, `catalog-service`, `inventory-service`, `gateway`.
- Padrão de Dockerfile fixado em `eclipse-temurin:21.x.x` já estabelecido — replicar para `notification-service`.
- `docker-compose.yml` já sobe LocalStack com `SERVICES=sqs,dynamodb` e `LOCALSTACK_AUTH_TOKEN` — falta apenas o provisionamento automático da fila/tabela (Success Criteria 4) e o serviço `notification-service` em si.
- Endpoint de ajuste de estoque (`PUT /inventory/{productId}/stock`, INV-01) já existe no inventory-service da Fase 2 — é o ponto de inserção da publicação do evento.

### Established Patterns
- Validação de JWT via Spring Security OAuth2 Resource Server, JWKS do auth-service, sem chamada síncrona — `notification-service` replica a configuração de `catalog-service`/`inventory-service`.
- `docker-compose.yml` usa `depends_on` + `service_healthy` + healthcheck via `/actuator/health` em todo serviço novo — aplicar a `notification-service`, com `depends_on: localstack: condition: service_healthy`.
- Pacotes por domínio com `dto` aninhado (`com.orderflow.<domain>.<subdomain>.dto.*`) — replicar em `com.orderflow.notification.*`.

### Integration Points
- Gateway precisa de nova rota estática para `notification-service` (mesmo padrão de rota por serviço já usado).
- inventory-service ganha uma dependência nova de publicação (Spring Cloud AWS `SqsTemplate`) no fluxo já existente de `PUT stock` — ponto de inserção após o commit bem-sucedido do ajuste (incluindo qualquer retry de lock otimista, D-20/D-21 da Fase 2).
- README.md precisa ser atualizado com o novo endpoint de consulta e um passo a passo de como observar o evento fluindo (SQS → DynamoDB) — padrão já estabelecido nas fases anteriores.

</code_context>

<specifics>
## Specific Ideas

- Evento de prova = ajuste de estoque, não reserva/liberação — menor escopo possível para provar o encanamento.
- Outbox fica para a Fase 5; aqui é publicação direta, com o dual-write documentado como limitação conhecida.
- Consulta por lista (Query por `productId`), não por item único — pensando em reaproveitar para a timeline de pedido da Fase 6.
- Sort key = `eventType` + identificador único do evento — resolve a diferença entre "reentrega" (sobrescreve) e "novo evento do mesmo tipo" (nova linha), evitando que o histórico vire só um "último estado conhecido".
- Registro guarda payload bruto + mensagem legível — pensando no avaliador externo que vai abrir o Swagger e querer entender o que aconteceu sem ler código.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 3-Primeira Integração Assíncrona — Histórico de Notificações*
*Context gathered: 2026-09-22*
