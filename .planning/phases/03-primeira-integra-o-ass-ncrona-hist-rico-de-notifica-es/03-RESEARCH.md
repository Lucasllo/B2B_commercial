# Phase 3: Primeira Integração Assíncrona — Histórico de Notificações - Research

**Researched:** 2026-09-22
**Domain:** Spring Cloud AWS messaging (SQS) + AWS SDK v2 DynamoDB Enhanced Client, both via LocalStack, in a Java 21/Spring Boot 3.5 Maven multi-module reactor
**Confidence:** MEDIUM (official docs and Maven Central cross-checked for versions/API shape; some exact method signatures on `DynamoDbTemplate` could not be pulled from primary source text and are flagged below)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-27:** O evento publicado nesta fase é o **ajuste de quantidade em estoque** (`PUT /inventory/{productId}/stock`, INV-01) — a ação de upsert já existente da Fase 2, exatamente o exemplo citado no ROADMAP.md. — Reversibility: reversible.
- **D-28:** **Reservar** e **liberar** estoque (endpoints existentes da Fase 2) **não** publicam evento nesta fase — escopo mínimo deliberado. — Reversibility: reversible.
- **D-29:** O inventory-service publica o evento de ajuste de estoque **direto no SQS**, sem tabela Transactional Outbox nesta fase. O padrão Outbox é introduzido na Fase 5. — Reversibility: costly.
- **D-30:** O risco de dual-write fica **sem mitigação técnica** nesta fase — deve ser documentado como **limitação conhecida** (ADR ou nota no README). — Reversibility: reversible.
- **D-31:** O endpoint de consulta devolve uma **lista de eventos por identificador da entidade** (`GET /notifications/{productId}`, DynamoDB **Query** por partition key), não `GetItem` único. — Reversibility: costly.
- **D-32:** Partition key da tabela DynamoDB = **`productId`** nesta fase. Fase 4/6 passa a usar `orderId` como partition key para eventos de pedido, mesmo padrão de chave, entidade diferente, sem indireção genérica agora. — Reversibility: reversible.
- **D-33:** Sort key = **`eventType` + identificador único do evento** (gerado no momento da publicação), não apenas `eventType`. Reentrega da mesma mensagem → mesmo identificador → sobrescreve (Success Criteria 3). Evento novo do mesmo tipo → identificador diferente → nova linha. — Reversibility: one-way.
- **D-34:** O registro grava **payload bruto do evento** e uma **mensagem legível** gerada a partir dele. Ambos os campos. — Reversibility: reversible.
- **D-35:** Dois ajustes de estoque distintos no mesmo produto geram **duas linhas separadas** (consequência de D-33). — Reversibility: reversible.

### Claude's Discretion

- Nome exato do evento/tipo e do payload DTO compartilhado entre inventory-service e notification-service.
- Nome exato da fila SQS e da tabela DynamoDB, e mecanismo de provisionamento automático no LocalStack (script de init vs `awslocal` em entrypoint).
- Uso de `dynamodb-enhanced` (`@DynamoDbBean`) vs cliente SDK direto para o repositório do notification-service.
- Papel/JWT exigido para consultar o endpoint de histórico (SELLER_ADMIN apenas, ou qualquer usuário autenticado).
- Formato exato da mensagem legível (template string vs biblioteca de formatação).

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope.

</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| NOTF-01 | Notification-service consome eventos do ciclo de vida do pedido e os grava no DynamoDB (via LocalStack) | Standard Stack (Spring Cloud AWS `@SqsListener` + `spring-cloud-aws-starter-dynamodb`/`DynamoDbTemplate`), Architecture Patterns (Pattern 1: Fan-out SQS consumer → DynamoDB sink), Code Examples (consumer + `NotificationRecord` mapping), Common Pitfalls (idempotent overwrite via deterministic key) |
| NOTF-02 | Histórico de notificações de um pedido pode ser consultado | Architecture Patterns (Pattern 2: Query-by-partition-key endpoint), Code Examples (`GET /notifications/{productId}` via DynamoDB `Query`), Security Domain (JWT role requirement for the query endpoint) |

</phase_requirements>

## Summary

Esta fase adiciona o quinto módulo Maven (`notification-service`) e conecta, pela primeira vez, dois serviços via mensageria assíncrona: `inventory-service` publica um evento de ajuste de estoque no SQS (via LocalStack) logo após o commit do `PUT /inventory/{productId}/stock` já existente, e `notification-service` consome esse evento e grava um registro no DynamoDB. A pesquisa confirma que a stack recomendada em `.claude/CLAUDE.md` (Spring Cloud AWS `SqsTemplate`/`@SqsListener`, AWS SDK v2 DynamoDB Enhanced Client via `spring-cloud-aws-starter-dynamodb`) está disponível e correta para este projeto, com uma ressalva importante de versão: **a versão mais recente do Spring Cloud AWS (4.1.x) exige Spring Boot 4.0 / Spring Cloud 2025.1.x — incompatível com o reactor deste projeto (Spring Boot 3.5.16 + Spring Cloud 2025.0.3 "Northfields")**. A linha correta a fixar é **Spring Cloud AWS 3.4.x**, cuja última versão de patch é **3.4.2**, confirmada tanto no Maven Central quanto no `pom.xml` pai publicado do próprio projeto `spring-cloud-aws` (que declara Spring Boot 3.5.x / Spring Cloud 2025.0.x como o par compatível).

A pesquisa também resolve, com uma fonte oficial da AWS, a dúvida central por trás de D-33: `PutItem` no DynamoDB **substitui silenciosamente** um item existente com a mesma chave primária quando nenhuma `ConditionExpression` é enviada — ou seja, a estratégia de "reentrega sobrescreve, evento novo insere" descrita no CONTEXT.md funciona automaticamente com uma chamada `save()`/`PutItem` simples, sem lógica de deduplicação manual no lado do consumidor. Isso é o principal item "don't hand-roll" desta fase.

Um ponto de atenção descoberto na pesquisa e ausente do CONTEXT.md: o **Spring Cloud AWS ainda não oferece suporte nativo a `@ServiceConnection`** para `LocalStackContainer` (ao contrário do Postgres, que já usa esse padrão em `AbstractIntegrationTest` de `inventory-service`) — o teste de integração desta fase precisa registrar manualmente as propriedades `spring.cloud.aws.*` via `@DynamicPropertySource`, não via `@ServiceConnection`.

**Primary recommendation:** Fixar `io.awspring.cloud:spring-cloud-aws-dependencies:3.4.2` como BOM no `pom.xml` raiz (ao lado dos BOMs já existentes), usar `spring-cloud-aws-starter-sqs` em `inventory-service` (produtor) e `notification-service` (consumidor), e `spring-cloud-aws-starter-dynamodb` (que traz `DynamoDbTemplate`/`DynamoDbEnhancedClient` transitivamente) apenas em `notification-service`. Publicar o evento a partir do `InventoryController` (não de dentro do método `@Transactional` de `InventoryService`), depois que a resposta do `setStock` já foi obtida — garante que o SQS só recebe a mensagem depois que o commit no Postgres já aconteceu, o mais próximo que dá para chegar da garantia do outbox sem construir o outbox agora (D-29/D-30).

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Publicar evento de ajuste de estoque | API / Backend (inventory-service) | — | O evento nasce como efeito colateral de um endpoint REST já existente; a publicação é responsabilidade do próprio serviço dono do dado, não de um serviço intermediário |
| Transporte assíncrono do evento | Messaging (SQS via LocalStack) | — | Desacopla produtor e consumidor no tempo; nenhuma camada de aplicação deve tentar substituir essa função (ex.: nenhuma chamada REST direta) |
| Consumir evento e persistir histórico | API / Backend (notification-service) | Database/Storage (DynamoDB) | notification-service é um sink puro — a lógica de mapeamento evento→registro vive no backend, a durabilidade vive no DynamoDB |
| Idempotência de reentrega | Database/Storage (DynamoDB, via chave determinística) | Backend (montagem da chave) | A garantia de "sobrescrever, não duplicar" é delegada à semântica nativa de `PutItem` sobre uma chave calculada corretamente no backend — nenhuma tabela de deduplicação própria é necessária |
| Consulta do histórico por entidade | API / Backend (notification-service) | Gateway (roteamento) | O endpoint de leitura pertence ao mesmo serviço que é dono dos dados; o Gateway apenas expõe a rota, sem lógica própria |
| Provisionamento de fila/tabela | Infrastructure (LocalStack init hooks) | — | Deve acontecer na subida do container, fora do ciclo de vida da aplicação Spring — nenhum serviço Java deve criar sua própria fila/tabela em tempo de execução (isso quebraria "sem passo manual" e misturaria responsabilidade de infraestrutura com lógica de negócio) |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `io.awspring.cloud:spring-cloud-aws-dependencies` (BOM) | **3.4.2** | Gerencia as versões de `spring-cloud-aws-starter-sqs`, `spring-cloud-aws-starter-dynamodb` e do AWS SDK v2 transitivo (`awssdk-v2.version=2.31.78`, confirmado no `pom.xml` publicado do artefato) | `4.1.x` é a versão mais recente do Spring Cloud AWS no Maven Central, mas exige Spring Boot 4.0/Spring Cloud 2025.1.x; `3.4.x` é a última linha compatível com Spring Boot 3.5.x/Spring Cloud 2025.0.3 "Northfields", que é exatamente o que o reactor deste projeto já fixa (`spring-boot.version=3.5.16`, `spring-cloud.version=2025.0.3` no `pom.xml` raiz [VERIFIED: pom.xml:33-35]). [CITED: repo1.maven.org/.../spring-cloud-aws-starter-sqs/3.4.2 (pom parent confirma versão 3.4.2), cross-checado com WebSearch sobre a matriz de compatibilidade do awspring/spring-cloud-aws] |
| `io.awspring.cloud:spring-cloud-aws-starter-sqs` | 3.4.2 (via BOM) | `SqsTemplate` para publicar, `@SqsListener` para consumir | Starter oficial recomendado pelo próprio time do Spring Cloud AWS para mensageria SQS idiomática, evita boilerplate do `SqsAsyncClient` cru. Confidence: MEDIUM [CITED: docs.awspring.io, Baeldung, howtodoinjava.com — cross-checados] |
| `io.awspring.cloud:spring-cloud-aws-starter-dynamodb` | 3.4.2 (via BOM) | Autoconfigura `DynamoDbClient`, `DynamoDbEnhancedClient` e `DynamoDbTemplate` a partir das mesmas propriedades `spring.cloud.aws.*` usadas pelo SQS | Evita configurar manualmente um `DynamoDbEnhancedClient` como `@Bean`; mesma família de propriedades (`spring.cloud.aws.dynamodb.endpoint`) que o restante do projeto já usa para outras integrações AWS. Confidence: MEDIUM [CITED: docs.awspring.io reference guide 3.4.2] |
| `software.amazon.awssdk:dynamodb-enhanced` | gerenciado transitivamente pelo BOM acima (não declarar versão à mão) | Mapeamento `@DynamoDbBean` do registro de notificação | Vem transitivamente de `spring-cloud-aws-starter-dynamodb` — declarar uma versão própria arriscaria divergir da versão que o BOM do Spring Cloud AWS já testa junto com o `SqsAsyncClient`. Última versão standalone no Maven Central é 2.55.3 [VERIFIED: repo1.maven.org/maven2/software/amazon/awssdk/dynamodb-enhanced/maven-metadata.xml], mas o BOM acima fixa 2.31.78 — **não sobrescrever essa versão manualmente**. |
| `org.testcontainers:localstack` | 1.20.4 (mesma `testcontainers.version` já fixada no `pom.xml` raiz) | Container LocalStack real para o teste de integração SQS+DynamoDB | Reaproveita a mesma versão do BOM `testcontainers-bom` já importado — evita divergência de versão entre módulos. Confirmado que 1.20.4 existe no artefato `org.testcontainers:localstack` [VERIFIED: repo1.maven.org/maven2/org/testcontainers/localstack/maven-metadata.xml] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Lombok | 1.18.34 (já fixado no reactor) | Getters/setters no `@DynamoDbBean` | O AWS SDK Enhanced Client exige getters/setters públicos padrão (não campos `final`) — diferente do estilo `@Getter` + métodos de negócio já usado em `Inventory.java`; usar `@Getter @Setter @NoArgsConstructor` no bean do DynamoDB, não o padrão `@NoArgsConstructor(PROTECTED)` das entidades JPA |
| Jackson (`spring-boot-starter-web`, já transitivo) | gerenciado pelo BOM do Spring Boot | Serializar/desserializar o payload do evento (JSON) na mensagem SQS e no campo "payload bruto" gravado no DynamoDB | `SqsTemplate`/`@SqsListener` já usam Jackson por baixo dos panos para converter POJOs↔corpo da mensagem quando o tipo do parâmetro do listener não é `String` |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `spring-cloud-aws-starter-dynamodb` (`DynamoDbTemplate`) | `software.amazon.awssdk:dynamodb-enhanced` direto, com `DynamoDbEnhancedClient` configurado manualmente como `@Bean` | Mais controle fino sobre a configuração do cliente, mas reintroduz o boilerplate que o starter já resolve (região, endpoint, credenciais) — sem ganho real neste projeto, já que o starter usa exatamente as mesmas classes por baixo |
| Fila única de fan-out (`notification-events-queue`) reutilizável por futuros produtores | Uma fila por tipo de evento/serviço produtor | Fila única é mais simples de provisionar agora e já serve a Fase 4/6 (eventos de pedido) sem precisar criar filas novas depois; múltiplas filas só valeria a pena se os consumidores precisassem de políticas de retry/DLQ diferentes por tipo de evento — não é o caso aqui (nenhuma DLQ nesta fase, ver Deferred v2 `DLQ-01`) |
| DTO de evento duplicado (não compartilhado) entre `inventory-service` e `notification-service` | Módulo Maven `common/events` compartilhado | Um módulo `common` novo tocaria a estrutura do reactor (5º módulo extra) só para 1-2 records nesta fase; ARCHITECTURE.md já registra essa mesma dúvida e recomenda "duplicar o schema do evento por serviço, tratando o payload JSON como o contrato" quando o ganho de um módulo compartilhado é marginal — esse é o caso aqui. Revisitar se a Fase 5 (saga) tornar os contratos compartilhados evidentemente mais numerosos. |

**Installation (root `pom.xml`, `dependencyManagement`):**
```xml
<properties>
    <!-- ... propriedades existentes ... -->
    <spring-cloud-aws.version>3.4.2</spring-cloud-aws.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- ... BOMs existentes ... -->
        <dependency>
            <groupId>io.awspring.cloud</groupId>
            <artifactId>spring-cloud-aws-dependencies</artifactId>
            <version>${spring-cloud-aws.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<modules>
    <!-- ... módulos existentes ... -->
    <module>notification-service</module>
</modules>
```

**`inventory-service/pom.xml` (produtor):**
```bash
io.awspring.cloud:spring-cloud-aws-starter-sqs   # sem versão — vem do BOM
```

**`notification-service/pom.xml` (consumidor + persistência):**
```bash
io.awspring.cloud:spring-cloud-aws-starter-sqs
io.awspring.cloud:spring-cloud-aws-starter-dynamodb
org.testcontainers:localstack   # scope test
```

**Version verification performed:** `spring-cloud-aws-starter-sqs`, `spring-cloud-aws-starter-dynamodb` (3.4.2), `software.amazon.awssdk:dynamodb-enhanced`/BOM (2.55.3 standalone latest, but managed at 2.31.78 by the Spring Cloud AWS BOM), and `org.testcontainers:localstack` (1.20.4) were all confirmed to exist via direct Maven Central `maven-metadata.xml`/pom queries this session — see command outputs above.

## Package Legitimacy Audit

> The automated `package-legitimacy check` seam only supports `npm|pypi|crates` ecosystems; this phase is 100% Maven, so no automated verdict tool was available. Verification below was performed manually against Maven Central (the authoritative registry) plus each project's own official documentation/GitHub org.

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| `io.awspring.cloud:spring-cloud-aws-dependencies` | Maven Central | Active since 2020s, latest release days-old at research time | Not surfaced by Maven Central UI (no npm-style download counts) | github.com/awspring/spring-cloud-aws (official Spring community project, org endorsed by Spring/AWS) | OK | Approved |
| `io.awspring.cloud:spring-cloud-aws-starter-sqs` | Maven Central | Same project as above | — | github.com/awspring/spring-cloud-aws | OK | Approved |
| `io.awspring.cloud:spring-cloud-aws-starter-dynamodb` | Maven Central | Same project as above | — | github.com/awspring/spring-cloud-aws | OK | Approved |
| `software.amazon.awssdk:dynamodb-enhanced` | Maven Central | Official AWS SDK for Java v2, first-party AWS package | — | github.com/aws/aws-sdk-java-v2 | OK | Approved |
| `org.testcontainers:localstack` | Maven Central | Official Testcontainers module, already used pattern in project (`postgresql` module) | — | github.com/testcontainers/testcontainers-java | OK | Approved |

**Packages removed due to [SLOP] verdict:** none.
**Packages flagged as suspicious [SUS]:** none — all five packages are official, first-party artifacts from well-known groupIds (`io.awspring.cloud`, `software.amazon.awssdk`, `org.testcontainers`) already partially trusted by this project's own `.claude/CLAUDE.md` stack research, and all versions were directly confirmed to exist on Maven Central this session.

## Architecture Patterns

### System Architecture Diagram

```
inventory-service                    SQS (LocalStack)              notification-service            DynamoDB (LocalStack)
┌──────────────────────┐             ┌───────────────────┐          ┌───────────────────────┐        ┌───────────────────┐
│ PUT /inventory/{id}/  │             │ notification-      │         │ @SqsListener           │        │ notification-      │
│ stock (existing,      │  1. commit  │ events-queue        │         │ (queue consumer)       │        │ history (table)    │
│ Fase 2)               │─────────────▶  (fan-out, single   │────────▶│                        │───────▶│ PK=productId        │
│  └─ InventoryService  │  to Postgres│   queue, future      │ 3. poll │  ├─ map event → record │ 5. Put │ SK=eventType#eventId│
│     .setStock(...)    │  (already   │   phases add more    │         │  ├─ build human-       │  Item  │                     │
│  └─ InventoryController│  works)     │   producers)         │         │  │   readable message  │        │                     │
│     publishes event   │  2. publish │                     │         │  └─ save via            │        │                     │
│     AFTER commit       │────────────▶│                     │         │      DynamoDbTemplate    │        │                     │
└──────────────────────┘             └───────────────────┘          └───────────────────────┘        └─────────┬─────────┘
                                                                                  ▲                              │
                                                                                  │ 4. GET /notifications/{id}   │ 6. Query by
                                                                     Gateway ─────┘  (via Gateway route)         │ partition key
                                                                                                                  ▼
                                                                                                     JSON list of NotificationRecord
```

A reader trace: an existing REST call (1) commits a stock change to Postgres exactly as it does today; only *after* that commit succeeds does the controller (2) publish a small event to a single fan-out SQS queue; `notification-service`'s listener (3) picks it up, maps it to a `NotificationRecord` with a deterministic key, and (5) writes it to DynamoDB — an overwrite if the same event id/type shows up again. Independently, any authenticated caller can (4) hit a read endpoint through the Gateway that (6) queries DynamoDB by partition key and returns the full list of records for that entity.

### Recommended Project Structure

```
notification-service/
├── Dockerfile                       # replica do padrão dos outros 4 serviços (eclipse-temurin:21.0.12_8)
├── pom.xml
└── src/main/java/com/orderflow/notification/
    ├── NotificationServiceApplication.java
    ├── config/
    │   ├── SecurityConfig.java      # réplica do padrão de inventory-service/catalog-service
    │   ├── OpenApiConfig.java       # réplica do padrão existente
    │   └── GlobalExceptionHandler.java
    └── history/
        ├── NotificationRecord.java        # @DynamoDbBean
        ├── NotificationRepository.java    # thin wrapper sobre DynamoDbTemplate
        ├── NotificationService.java       # lógica de mapeamento evento→registro, mensagem legível
        ├── NotificationController.java    # GET /notifications/{productId}
        ├── dto/
        │   ├── StockAdjustedEvent.java    # payload consumido do SQS (duplicado do lado do inventory-service, não compartilhado — ver Alternatives Considered)
        │   └── NotificationResponse.java  # DTO de resposta do endpoint de consulta
        └── messaging/
            └── StockAdjustedEventListener.java  # @SqsListener

inventory-service/src/main/java/com/orderflow/inventory/
└── stock/
    ├── dto/
    │   └── StockAdjustedEvent.java    # mesmo shape JSON do lado do notification-service
    └── messaging/
        └── StockEventPublisher.java  # SqsTemplate.send(...), chamado pelo InventoryController após setStock()
```

### Structure Rationale

- **`messaging/` como pacote irmão de `stock/` (ou `history/`) em cada serviço**, seguindo a mesma convenção de pacote-por-domínio já usada em `inventory-service` — reforça que mensageria é um detalhe de infraestrutura do domínio, não um domínio à parte.
- **Publicação fora do `@Transactional`:** `StockEventPublisher` é chamado do `InventoryController`, depois que `inventoryService.setStock(...)` já retornou — o proxy Spring já commitou a transação antes de devolver o controle ao controller, então o SQS nunca recebe uma mensagem para um commit que ainda pode falhar. Isso não é o outbox pattern (nenhuma tabela de eventos pendentes, nenhuma garantia atômica real — D-29/D-30), mas evita o pior caso óbvio de publicar antes do commit.
- **DTO de evento duplicado, não compartilhado:** ver "Alternatives Considered" acima — evita adicionar um 6º módulo Maven só para 1-2 records nesta fase.

## Pattern 1: Fan-out SQS consumer → DynamoDB sink (NOTF-01)

**What:** `notification-service` expõe um único método anotado `@SqsListener("notification-events-queue")`, recebe o evento desserializado (Jackson faz a conversão automaticamente quando o parâmetro do método não é `String`), monta um `NotificationRecord` com chave determinística, e grava via `DynamoDbTemplate.save(...)`.

**When to use:** Sempre que um evento de ciclo de vida precisa virar histórico consultável, sem que o produtor precise saber nada sobre notification-service (ele só publica na fila).

**Example (padrão de código, nomes exatos ficam a cargo do plano):**
```java
// notification-service — messaging/StockAdjustedEventListener.java
@Component
public class StockAdjustedEventListener {

    private final NotificationService notificationService;

    public StockAdjustedEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @SqsListener("notification-events-queue")
    public void onStockAdjusted(StockAdjustedEvent event) {
        notificationService.recordStockAdjustment(event);
    }
}
```
```java
// notification-service — history/NotificationService.java
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public void recordStockAdjustment(StockAdjustedEvent event) {
        String sortKey = event.eventType() + "#" + event.eventId(); // D-33
        String message = "Estoque do produto %s ajustado de %d para %d unidades"
                .formatted(event.productId(), event.previousQuantityOnHand(), event.newQuantityOnHand());

        NotificationRecord record = new NotificationRecord();
        record.setProductId(event.productId().toString());   // D-32: partition key
        record.setSortKey(sortKey);
        record.setEventType(event.eventType());
        record.setEventId(event.eventId().toString());
        record.setRawPayload(event.toRawJson());              // D-34: payload bruto
        record.setMessage(message);                            // D-34: mensagem legível
        record.setOccurredAt(event.occurredAt());
        record.setRecordedAt(Instant.now());

        notificationRepository.save(record); // PutItem — overwrite automático em reentrega (ver Don't Hand-Roll)
    }
}
```

## Pattern 2: Query-by-partition-key read endpoint (NOTF-02)

**What:** `GET /notifications/{productId}` consulta o DynamoDB com `Query` (não `GetItem`) sobre a partition key, devolvendo todos os itens daquele produto — reaproveitável pela Fase 6 trocando apenas o nome do identificador (`orderId`).

**Example:**
```java
// notification-service — history/NotificationRepository.java
@Repository
public class NotificationRepository {

    private final DynamoDbTemplate dynamoDbTemplate;

    public NotificationRepository(DynamoDbTemplate dynamoDbTemplate) {
        this.dynamoDbTemplate = dynamoDbTemplate;
    }

    public void save(NotificationRecord record) {
        dynamoDbTemplate.save(record);
    }

    public List<NotificationRecord> findByProductId(String productId) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(productId).build());
        // ATENÇÃO: confirmar a assinatura exata do método de query do DynamoDbTemplate
        // (io.awspring.cloud.dynamodb.DynamoDbTemplate) na versão 3.4.2 antes de codificar —
        // não foi possível confirmar a assinatura exata via fonte primária nesta pesquisa
        // (ver Assumptions Log, item A2). Alternativa de fallback comprovada: usar
        // DynamoDbEnhancedClient.table(...).query(condition) diretamente.
        return dynamoDbTemplate.query(NotificationRecord.class, condition)
                .items().stream().toList();
    }
}
```
```java
// notification-service — history/NotificationController.java
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping("/{productId}")
    public List<NotificationResponse> getHistory(@PathVariable String productId) {
        return notificationRepository.findByProductId(productId).stream()
                .map(NotificationResponse::from)
                .toList();
    }
}
```

### Anti-Patterns to Avoid

- **Chamar `sqsTemplate.send(...)` de dentro do método `@Transactional setStock(...)`:** se a mensagem for enviada antes do commit e a transação depois falhar (ex.: reexecução esgotada de `@Retryable`, D-21), o evento publicado descreve uma mudança que nunca aconteceu. Publicar depois que o método `@Transactional` retorna (fora do próprio bean, no controller) evita esse caso específico — não é o outbox, mas é estritamente melhor que publicar antes do commit.
- **Construir uma tabela/lógica de deduplicação própria no notification-service** (ex.: `SELECT antes de INSERT`, ou uma tabela de "eventos já processados"): desnecessário — `PutItem` já sobrescreve por padrão sobre a mesma chave primária (ver Don't Hand-Roll).
- **Assumir que `@ServiceConnection` conecta o `LocalStackContainer` automaticamente** como acontece com o `PostgreSQLContainer` em `AbstractIntegrationTest.java` — Spring Cloud AWS ainda não oferece esse suporte nativamente (ver Common Pitfalls).
- **Usar `record` Java para `NotificationRecord`:** `@DynamoDbBean` exige campos mutáveis (não-`final`) com getters/setters padrão — um `record` (campos implicitamente `final`) não é compatível com essa anotação; usar uma classe simples com Lombok `@Getter @Setter @NoArgsConstructor`, deliberadamente diferente do estilo de entidade JPA (`@NoArgsConstructor(PROTECTED)` + métodos de negócio) já usado em `Inventory.java`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Idempotência de reentrega (Success Criteria 3, D-33) | Uma checagem manual "já processei este evento?" antes de gravar | Uma chave primária composta determinística (`productId` + `eventType#eventId`) e uma chamada `save()`/`PutItem` simples | `PutItem` do DynamoDB **substitui completamente** um item existente com a mesma chave primária quando nenhuma `ConditionExpression` é enviada — confirmado na referência oficial da API: *"Creates a new item, or replaces an old item with a new item. If an item that has the same primary key as the new item already exists in the specified table, the new item completely replaces the existing item."* [VERIFIED: docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_PutItem.html] |
| Configuração manual de `DynamoDbEnhancedClient`/`SqsAsyncClient` como `@Bean` | Um `@Configuration` que constrói os clientes AWS na mão (região, endpoint, credenciais) | `spring-cloud-aws-starter-sqs` + `spring-cloud-aws-starter-dynamodb`, configurados só via `application.yml` (`spring.cloud.aws.*`) | Os starters já autoconfiguram `SqsTemplate`, `DynamoDbClient`, `DynamoDbEnhancedClient` e `DynamoDbTemplate` a partir das mesmas propriedades — construir na mão duplica o que o starter já resolve e é a fonte mais comum de bugs sutis de configuração (região errada, endpoint não aplicado a um cliente específico) |
| Provisionamento de fila/tabela em tempo de subida da aplicação Java | Um `CommandLineRunner`/`ApplicationRunner` que cria a fila/tabela se não existir | LocalStack **init hooks** (`/etc/localstack/init/ready.d/*.sh` com `awslocal`), executados na subida do próprio container LocalStack | Success Criteria 4 exige que isso aconteça "sem passo manual" na subida do LocalStack — colocar essa responsabilidade dentro do código Java acopla infraestrutura a lógica de aplicação e falha se dois serviços tentarem criar o mesmo recurso ao mesmo tempo (condição de corrida na subida do `docker-compose up`) |

**Key insight:** A maior armadilha desta fase não é escrever código de mais — é escrever lógica de idempotência que o próprio DynamoDB já oferece de graça através do desenho de chave (D-32/D-33). Se o time de planejamento perceber qualquer código que verifica "este evento já existe?" antes de um `save()`, é sinal de que a chave primária foi desenhada errado, não de que falta uma checagem.

## Common Pitfalls

### Pitfall A: `@ServiceConnection` não conecta LocalStack automaticamente

**What goes wrong:** O desenvolvedor tenta replicar o padrão exato de `AbstractIntegrationTest.java` (`@ServiceConnection static final PostgreSQLContainer<?> postgres = ...`) trocando `PostgreSQLContainer` por `LocalStackContainer`, esperando que Spring Boot autoconfigure `spring.cloud.aws.*` sozinho. O teste sobe, mas o cliente SQS/DynamoDB tenta falar com a AWS real (ou falha por falta de credenciais), porque nenhuma propriedade foi de fato registrada.

**Why it happens:** `@ServiceConnection` tem suporte nativo consolidado para bancos relacionais (Postgres, MySQL) há mais tempo; o suporte para Spring Cloud AWS + `LocalStackContainer` ainda não existe de forma pronta no framework — confirmado via pesquisa: *"Spring Cloud AWS doesn't provide ServiceConnection support out-of-the-box yet"* [CITED: cross-checado via WebSearch/testcontainers.com guide + docs.spring.io/spring-boot testcontainers reference].

**How to avoid:** Registrar as propriedades manualmente via `@DynamicPropertySource`, apontando `spring.cloud.aws.sqs.endpoint`, `spring.cloud.aws.dynamodb.endpoint` (ou o `spring.cloud.aws.endpoint` genérico), `spring.cloud.aws.region.static` e `spring.cloud.aws.credentials.access-key`/`secret-key` (com valores fake, ex. `test`/`test`) para o endpoint do container `LocalStackContainer` iniciado com `.withServices(Service.SQS, Service.DYNAMODB)`.

**Warning signs:** Teste de integração passa localmente com timeout enorme (tentando alcançar a AWS real) ou falha com erro de credenciais/DNS ao invés de um erro de asserção de negócio.

### Pitfall B: Publicar antes do commit (variação local do dual-write, D-29/D-30)

**What goes wrong:** A chamada `sqsTemplate.send(...)` é colocada dentro do método `@Transactional setStock(...)` de `InventoryService` (ou pior, antes do `saveAndFlush`). Se a transação falhar depois (reexecução esgotada, D-21), um evento já foi publicado para uma mudança que nunca existiu no Postgres.

**How to avoid:** Publicar do `InventoryController`, depois que a chamada a `inventoryService.setStock(...)` já retornou (o proxy transacional já commitou nesse ponto). Isso não substitui o outbox da Fase 5, mas evita o caso mais óbvio de inconsistência.

**Warning signs:** Código de publicação do evento aparece dentro da mesma classe/método anotado `@Transactional` e `@Retryable` de `InventoryService`.

### Pitfall C: Capturar "quantidade anterior" com uma leitura separada antes do `PUT`

**What goes wrong:** Para montar o payload do evento (D-34 exige `previousQuantityOnHand`/`newQuantityOnHand`), é tentador fazer um `GET` interno ou uma leitura própria antes de chamar `setStock(...)`. Sob concorrência, essa leitura separada pode capturar um valor que já mudou entre a leitura e a escrita — reintroduzindo exatamente o tipo de corrida que `InventoryService` já evita fazendo tudo dentro de uma única transação (`InventoryRetryContentionIT`, Fase 2).

**Why it happens:** `StockResponse` (o retorno atual de `setStock`) só expõe o estado *depois* da mudança — não guarda a quantidade anterior, então parece natural buscar isso "de fora".

**How to avoid:** Capturar `quantityOnHand` **dentro** do mesmo método transacional `setStock`, antes de chamar `inventory.setOnHand(...)`, e devolver esse valor junto com a resposta (ex.: um tipo de retorno interno `StockAdjustmentResult(StockResponse response, int previousQuantityOnHand)` usado apenas pelo `InventoryController` para montar o evento — sem expor esse campo no contrato REST público de `StockResponse`, que já é consumido/documentado). Como o método inteiro é reexecutado em caso de conflito de versão (`@Retryable`), a captura acontece de novo a cada tentativa e nunca fica desatualizada.

**Warning signs:** Um novo endpoint `GET` "interno" aparece só para o publisher do evento consultar o estado anterior; ou o evento de ajuste sempre reporta `previousQuantityOnHand == newQuantityOnHand` (sinal de que a leitura aconteceu depois da escrita).

### Pitfall D: LocalStack init hooks não executam por causa do `PERSISTENCE=0`/montagem de volume incorreta

**What goes wrong:** O script de criação da fila/tabela é colocado no lugar errado (ex.: montado em `/docker-entrypoint-initaws.d/` — caminho de uma versão antiga do LocalStack, ou sem permissão de execução) e o LocalStack sobe "saudável" (healthcheck em `/_localstack/health` passa) mas sem fila nem tabela — o primeiro sintoma só aparece quando o serviço tenta publicar/consumir e recebe `QueueDoesNotExist`/`ResourceNotFoundException`.

**Why it happens:** O caminho correto mudou entre versões do LocalStack (a imagem já fixada no projeto é `localstack/localstack:2026.08.3`); o caminho atual e documentado é `/etc/localstack/init/ready.d/`, executado no estágio READY, em ordem alfanumérica.

**How to avoid:** Montar o script via `volumes:` no `docker-compose.yml` (`- ./localstack-init:/etc/localstack/init/ready.d`), garantir `chmod +x` no script, e verificar manualmente uma vez (`docker compose logs localstack | grep -i ready.d`) que o script foi descoberto e executado antes de assumir que "funciona".

**Warning signs:** `awslocal sqs list-queues`/`awslocal dynamodb list-tables` executado manualmente dentro do container mostra lista vazia mesmo depois do LocalStack reportar saudável.

## Code Examples

### Payload do evento (compartilhado por contrato JSON, não por módulo Maven)

```java
// Duplicado em inventory-service/stock/dto e notification-service/history/dto —
// mesmo shape JSON, ver "Alternatives Considered" sobre por que não é um módulo common/
public record StockAdjustedEvent(
        UUID eventId,                  // gerado uma única vez no momento da publicação (D-33) —
                                        // NÃO usar o messageId do SQS como identificador de dedupe:
                                        // esse id é da entrega, não do evento de domínio.
        String eventType,              // "STOCK_ADJUSTED"
        UUID productId,
        int previousQuantityOnHand,
        int newQuantityOnHand,
        Instant occurredAt
) {
    public static final String EVENT_TYPE = "STOCK_ADJUSTED";
}
```

### Publicação (inventory-service, depois do commit)

```java
// inventory-service — stock/messaging/StockEventPublisher.java
@Component
public class StockEventPublisher {

    private static final String QUEUE_NAME = "notification-events-queue";

    private final SqsTemplate sqsTemplate;

    public StockEventPublisher(SqsTemplate sqsTemplate) {
        this.sqsTemplate = sqsTemplate;
    }

    public void publishStockAdjusted(StockAdjustedEvent event) {
        // Falha de publicação aqui é uma limitação conhecida documentada (D-30) — não deve
        // derrubar a resposta HTTP 200 já obtida do ajuste de estoque, que já foi commitado
        // com sucesso no Postgres antes desta chamada.
        sqsTemplate.send(to -> to.queue(QUEUE_NAME).payload(event));
    }
}
```

### `application.yml` (notification-service, LocalStack)

```yaml
spring:
  cloud:
    aws:
      region:
        static: us-east-1
      credentials:
        access-key: test
        secret-key: test
      endpoint: ${AWS_ENDPOINT_URL:http://localhost:4566}   # sobrescrito por docker-compose para http://localstack:4566
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Spring Cloud AWS "Messaging" module (`spring-cloud-starter-aws-messaging`, versão 2.x) | Spring Cloud AWS 3.x com `spring-cloud-aws-starter-sqs`/`@SqsListener` (io.awspring.cloud) | Reescrita completa a partir da 3.0 (o projeto mudou de mantenedor/grupo) | Qualquer tutorial ou exemplo de código que use `@SqsListener` do pacote `org.springframework.cloud.aws.messaging` está desatualizado — o pacote correto agora é `io.awspring.cloud.sqs.annotation.SqsListener` |
| `DynamoDBMapper` (SDK v1) | DynamoDB Enhanced Client (`@DynamoDbBean`, SDK v2) | SDK v1 está em fim de suporte extendido pela AWS | Qualquer exemplo usando `@DynamoDBTable`/`@DynamoDBHashKey` (anotações do SDK v1) está errado para este projeto — as anotações corretas são `@DynamoDbBean`/`@DynamoDbPartitionKey`/`@DynamoDbSortKey` (SDK v2) |

**Deprecated/outdated:**
- `spring-cloud-starter-aws` / `spring-cloud-starter-aws-messaging` (grupo `org.springframework.cloud`): módulos antigos do Spring Cloud AWS 2.x, substituídos pelo projeto `io.awspring.cloud:spring-cloud-aws-*` a partir da versão 3.0.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Publicar o evento a partir do `InventoryController` (fora do `@Transactional`) é seguro e suficiente como mitigação parcial do dual-write nesta fase, sem quebrar nenhuma decisão travada | Architecture Patterns, Common Pitfalls B | Baixo — é uma recomendação de implementação, não uma decisão travada; se o plano preferir publicar de outro ponto, a decisão de "documentar dual-write como limitação conhecida" (D-30) continua válida de qualquer forma |
| A2 | A assinatura exata do método de consulta por partition key em `io.awspring.cloud.dynamodb.DynamoDbTemplate` (ex.: `query(Class, QueryConditional)` retornando algo iterável) não pôde ser confirmada a partir de texto de fonte primária nesta sessão — apenas que "adiciona métodos de conveniência para consultar o DynamoDB" foi confirmado via `docs.awspring.io` | Code Examples (Pattern 2) | Médio — se a assinatura estiver errada, o código não compila; mitigação de fallback já documentada no próprio exemplo (usar `DynamoDbEnhancedClient.table(...).query(...)` diretamente, API mais estável e amplamente documentada pela AWS) |
| A3 | Nome da fila SQS (`notification-events-queue`) e nome/atributos da tabela DynamoDB (partition key `productId`, sort key composta) usados nos exemplos são sugestões de nomenclatura — CONTEXT.md deixa isso a critério do planejamento | Architecture Patterns, Code Examples | Baixo — são apenas nomes; a estrutura de chave (não o nome literal da fila/tabela) é o que está travado por D-32/D-33 |
| A4 | Deixar o `PUT /inventory/{productId}/stock` retornar 200 mesmo se a publicação SQS falhar (ao invés de falhar a requisição HTTP) é a interpretação correta de "risco sem mitigação técnica, documentado como limitação conhecida" (D-30) | Code Examples (publicação) | Médio — se o avaliador/planejador preferir que a falha de publicação derrube a requisição (comportamento mais conservador, mas inconsistente com "o ajuste já foi commitado"), isso deve ser uma decisão explícita do plano, não assumida silenciosamente |

## Open Questions

1. **O endpoint `GET /notifications/{productId}` exige papel específico ou apenas autenticação?**
   - What we know: CONTEXT.md marca isso como discricionário; os demais endpoints de leitura do projeto (ex.: `GET /inventory/{productId}`, `GET /products`) usam `authenticated()` genérico, sem restrição de papel.
   - What's unclear: se o histórico de notificações deveria ser visível a qualquer usuário autenticado (comprador incluso) ou só a `SELLER_ADMIN`, dado que o evento de prova (ajuste de estoque) é uma ação interna do vendedor.
   - Recommendation: seguir o padrão já estabelecido em `InventoryController.getStock` (qualquer autenticado, sem restrição de papel) por consistência — mais fácil de relaxar do que apertar depois, e a Fase 6 provavelmente vai querer que o próprio comprador veja o histórico do seu pedido.

2. **Falha de publicação no SQS deve derrubar a resposta HTTP do `PUT /inventory/{productId}/stock`?**
   - What we know: D-30 documenta o dual-write como limitação conhecida, sem mitigação técnica.
   - What's unclear: se "sem mitigação técnica" implica também "sem tratamento de erro nenhum" (deixar a exceção subir e derrubar a resposta 200 já formada) ou se o publisher deve engolir a exceção e logar, mantendo o 200.
   - Recommendation: engolir e logar (ver Assumption A4) — mais alinhado com "o ajuste já aconteceu de verdade no Postgres", mas deixar como pergunta explícita para o plano confirmar.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker Engine | Testcontainers (LocalStack real), `docker-compose up` | ✓ | 29.8.0 (client) | — |
| Maven Wrapper (`./mvnw`) | Build do novo módulo `notification-service` | ✓ | Apache Maven 3.9.9 via wrapper | — |
| Java 21 (JDK) | Compilação de todos os módulos | ✓ | 21.0.10 (Oracle) | — |
| LocalStack (via docker-compose) | SQS + DynamoDB locais | ✓ (já configurado) | `localstack/localstack:2026.08.3`, `SERVICES=sqs,dynamodb` já presentes no `docker-compose.yml` [VERIFIED: docker-compose.yml:19-23] | — |
| `LOCALSTACK_AUTH_TOKEN` | LocalStack Hobby tier obrigatório desde 2026.03.0 | Depende do ambiente local do desenvolvedor — `.env.example` já documenta a variável, mas o valor real não é lido por esta pesquisa | — | Sem fallback: sem o token, o container LocalStack não sobe; isso já é um blocker conhecido de infraestrutura desde a Fase 1, não introduzido por esta fase |

**Missing dependencies with no fallback:** nenhuma nova — `LOCALSTACK_AUTH_TOKEN` já era um requisito de infraestrutura da Fase 1, apenas reafirmado aqui porque esta é a primeira fase que efetivamente usa SQS/DynamoDB via LocalStack.

**Missing dependencies with fallback:** nenhuma.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + `spring-boot-starter-test` + Testcontainers (`junit-jupiter`, `postgresql` já em uso; `localstack` novo nesta fase) |
| Config file | Nenhum arquivo de config dedicado — segue o padrão `AbstractIntegrationTest` (bloco `static` de inicialização de container) já usado em `inventory-service` |
| Quick run command | `./mvnw -pl notification-service -am test` |
| Full suite command | `./mvnw -pl notification-service -am verify` (roda `*IT.java` via `maven-failsafe-plugin`, já configurado no `pom.xml` raiz) |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| NOTF-01 | Evento de ajuste de estoque publicado por inventory-service é consumido e gravado no DynamoDB | integration (Testcontainers + LocalStack real, ponta a ponta: publica no SQS real, espera o consumo, lê o DynamoDB real) | `./mvnw -pl notification-service -am verify` | ❌ Wave 0 — precisa de `NotificationEventFlowIT.java` novo |
| NOTF-01 (reentrega) | Reentrega do mesmo evento sobrescreve, não duplica (Success Criteria 3) | integration (publica a mesma mensagem/evento duas vezes, assere uma única linha no DynamoDB) | mesma classe acima ou uma dedicada `NotificationIdempotencyIT.java` | ❌ Wave 0 |
| NOTF-02 | `GET /notifications/{productId}` devolve lista de eventos daquele produto | integration (`MockMvc` + DynamoDB real via Testcontainers, seguindo o padrão `InventoryControllerIT`) | `./mvnw -pl notification-service -am verify` | ❌ Wave 0 — precisa de `NotificationControllerIT.java` novo |
| NOTF-02 | Endpoint acessível via Gateway | manual/smoke (rota estática nova em `gateway/application.yml`, sem teste automatizado próprio no projeto — mesmo padrão dos demais serviços) | curl manual documentado no README | ❌ Wave 0 (config only, não requer teste de código) |

### Sampling Rate

- **Per task commit:** `./mvnw -pl notification-service -am test` (unitários rápidos, sem containers)
- **Per wave merge:** `./mvnw -pl notification-service -am verify` (inclui Testcontainers + LocalStack real)
- **Phase gate:** Suite completa (`./mvnw verify` na raiz do reactor) verde antes de `/gsd-verify-work`, incluindo o novo teste de fluxo SQS→DynamoDB

### Wave 0 Gaps

- [ ] `notification-service/src/test/java/com/orderflow/notification/AbstractIntegrationTest.java` — base de teste com `LocalStackContainer` estático (padrão singleton container, réplica do `AbstractIntegrationTest` de `inventory-service`, mas com `@DynamicPropertySource` no lugar de `@ServiceConnection` — ver Common Pitfalls A)
- [ ] `notification-service/src/test/java/com/orderflow/notification/NotificationEventFlowIT.java` — publica um `StockAdjustedEvent` real no SQS (via `SqsTemplate` de teste) e assere que o registro aparece no DynamoDB dentro de um tempo razoável (usar `Awaitility`, já citado em `.claude/CLAUDE.md` como biblioteca padrão do projeto para asserções assíncronas — ainda não presente em nenhum `pom.xml` do reactor, precisa ser adicionado)
- [ ] `notification-service/src/test/java/com/orderflow/notification/NotificationControllerIT.java` — `GET /notifications/{productId}` via `MockMvc`
- [ ] Dependência de teste nova: `org.awaitility:awaitility` (nenhum módulo do reactor a usa ainda — precisa ser adicionada ao `pom.xml` de `notification-service`, e possivelmente `inventory-service` se o teste ponta-a-ponta ficar hospedado lá)
- [ ] Framework install: nenhum além da dependência acima — `spring-boot-starter-test`, `testcontainers-bom` já presentes no reactor

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | Spring Security OAuth2 Resource Server, mesmo `jwk-set-uri` do auth-service (réplica exata de `SecurityConfig.java` de `inventory-service`) |
| V3 Session Management | n/a | API stateless, sem sessão — já coberto pelo padrão `SessionCreationPolicy.STATELESS` replicado |
| V4 Access Control | yes | `@PreAuthorize`/`.authenticated()` no `NotificationController` — ver Open Question 1 sobre exigir `SELLER_ADMIN` ou qualquer autenticado |
| V5 Input Validation | yes | `@PathVariable` de `productId` deve ser validado como formato de UUID/string aceitável antes de virar partition key; payload do evento consumido do SQS não deve ser confiado cegamente (evento malformado/JSON inválido deve ser tratado, não derrubar o listener) |
| V6 Cryptography | n/a nesta fase | Nenhuma criptografia nova introduzida — JWT já assinado pelo auth-service (herdado) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Consumidor SQS processa payload malformado/malicioso e lança exceção não tratada, travando o listener em loop de redelivery | Denial of Service | Tratar exceções de desserialização/mapeamento dentro do listener com um bloco try/catch dedicado, logando e (nesta fase, sem DLQ ainda — v2 `DLQ-01`) deixando a mensagem ser reprocessada dentro do limite de `maxReceiveCount` padrão do LocalStack, em vez de deixar a exceção subir sem controle |
| Enumeração de `productId` no endpoint de consulta por qualquer usuário autenticado (se a decisão do Open Question 1 for "qualquer autenticado") | Information Disclosure | Como o histórico de ajuste de estoque não é dado sensível por comprador (é dado do vendedor sobre o próprio catálogo), o risco é baixo nesta fase — mas vale registrar como precedente: a Fase 6, ao reusar este endpoint para `orderId`, PRECISA restringir por dono do pedido (BUYER só vê o próprio, D-XX futuro), o que este desenho de endpoint sozinho não impede |
| Falsificação de evento (alguém publica diretamente na fila SQS sem passar pelo `PUT /inventory`) | Spoofing | Fora de escopo real de ameaça para um projeto rodando 100% em LocalStack local sem exposição externa — mas vale nota em ADR: em produção real, a fila deveria ter uma política de acesso IAM restringindo quem pode `SendMessage` |

## Sources

### Primary (HIGH confidence)
- [DynamoDB PutItem API Reference — docs.aws.amazon.com](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_PutItem.html) - confirmado overwrite semantics (fetched directly this session)
- Maven Central `maven-metadata.xml`/pom queries (direct `curl`, this session): `io.awspring.cloud:spring-cloud-aws-starter-sqs`, `io.awspring.cloud:spring-cloud-aws-starter-dynamodb`, `software.amazon.awssdk:dynamodb-enhanced`, `software.amazon.awssdk:bom`, `org.testcontainers:localstack` — versões existentes confirmadas
- `pom.xml` raiz do próprio projeto (linhas 27-50) e `inventory-service/pom.xml`, `docker-compose.yml`, `InventoryService.java`, `InventoryController.java`, `Inventory.java`, `SecurityConfig.java`, `OpenApiConfig.java`, `GlobalExceptionHandler.java`, `AbstractIntegrationTest.java` — lidos diretamente nesta sessão

### Secondary (MEDIUM confidence)
- [Spring Cloud AWS docs (awspring.io), versão 3.4.2](https://docs.awspring.io/spring-cloud-aws/docs/3.4.2/reference/html/index.html) — `DynamoDbTemplate.save()`, `spring.cloud.aws.dynamodb.endpoint`
- [Introduction to Spring Cloud AWS 3.0 – SQS Integration — Baeldung](https://www.baeldung.com/java-spring-cloud-aws-v3-intro)
- [AWS SQS with Spring Cloud AWS and Spring Boot 3 — howtodoinjava.com](https://howtodoinjava.com/spring-cloud/aws-sqs-with-spring-cloud-aws/)
- [Get Started using the DynamoDB Enhanced Client API — AWS SDK for Java 2.x](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/ddb-en-client-getting-started.html)
- [Initialization Hooks — docs.localstack.cloud](https://docs.localstack.cloud/references/init-hooks/)
- [Testing AWS service integrations using LocalStack — testcontainers.com](https://testcontainers.com/guides/testing-aws-service-integrations-using-localstack/)
- [Testcontainers :: Spring Boot — docs.spring.io](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html) — cross-checked for `@ServiceConnection` LocalStack support gap
- GitHub `awspring/spring-cloud-aws` (compatibility notes for 3.4.x ↔ Spring Boot 3.5.x/Spring Cloud 2025.0.x, cross-checked with the confirmed Maven Central parent-pom version)

### Tertiary (LOW confidence)
- Various Medium/blog posts on Testcontainers + LocalStack + Spring Boot integration testing (used only to corroborate already-established patterns, not as the sole source for any specific claim)

## Metadata

**Confidence breakdown:**
- Standard stack (versions): MEDIUM-HIGH — versões existentes confirmadas diretamente no Maven Central; a matriz de compatibilidade (3.4.x ↔ Boot 3.5.x/Cloud 2025.0.x) vem de fontes secundárias cross-checadas, não de uma tabela oficial lida integralmente
- Architecture: MEDIUM — padrões (fan-out, query-by-partition-key, publicação pós-commit) são inferências sólidas a partir de CONTEXT.md + práticas documentadas, não uma receita única e oficial
- Pitfalls: MEDIUM — o gap de `@ServiceConnection` e a semântica de overwrite do `PutItem` são achados concretos e verificados; os demais pitfalls são extrapolações de boas práticas já aplicadas no restante do codebase

**Research date:** 2026-09-22
**Valid until:** ~30 dias (stack Spring/AWS relativamente estável, mas a linha `4.x` do Spring Cloud AWS está em movimento ativo — reconfirmar a versão 3.4.x antes de implementar caso o planejamento seja retomado depois de várias semanas)
