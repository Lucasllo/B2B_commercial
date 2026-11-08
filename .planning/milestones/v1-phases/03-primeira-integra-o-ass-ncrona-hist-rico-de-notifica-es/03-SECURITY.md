---
phase: "03"
slug: "primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es"
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: "2026-09-24"
---

# Phase 03 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Cliente → Gateway → notification-service | Consulta HTTP do histórico com JWT Bearer | JWT (papel/empresa), histórico de ajustes de estoque |
| inventory-service → SQS (LocalStack) | Publicação do evento `STOCK_ADJUSTED` após commit | Evento de contrato (productId, quantidades, eventId) |
| SQS → notification-service → DynamoDB | Consumo do evento e gravação do histórico | Evento não confiável (validado antes de gravar) |
| Testes → LocalStack (Testcontainers) | Suítes de integração | `LOCALSTACK_AUTH_TOKEN` (segredo) |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-03-01 | Elevation of Privilege | `GET /notifications/{productId}` | high | mitigate | `@PreAuthorize("hasRole('SELLER_ADMIN')")` + `@EnableMethodSecurity`; `NotificationControllerIT` prova 403 para BUYER | closed |
| T-03-02 | Spoofing | Resource servers (notification/catalog/inventory) | high | mitigate | `jwk-set-uri` (RS256) + `issuer-uri: orderflow-auth-service` → `JwtValidators.createDefaultWithIssuer`; testes rodam sobre o decoder de produção e provam 401 para outra chave, `exp` vencido, `iss` errado e `iss` ausente (quick 260923-tj9, commits 56f9117/e05083a/eac39a8) | closed |
| T-03-03 | Denial of Service | Listener SQS (mensagem venenosa) | high | mitigate | `NotificationService` valida e lança `InvalidNotificationEventException`; listener descarta com WARN e confirma; `setPayloadTypeMapper(message -> null)`; `NotificationDeliveryIT` | closed |
| T-03-04 | Tampering | Reentrega do SQS | medium | mitigate | Chave `productId` + `STOCK_ADJUSTED#<eventId>`, `putItem` sem condição; teste de reentrega + smoke (1 elemento) | closed |
| T-03-05 | Tampering | Partition key a partir de input | low | mitigate | `@PathVariable UUID` → 400 `invalid_identifier`; `QueryConditional` com placeholder | closed |
| T-03-06 | Information Disclosure | Corpo de erro | medium | mitigate | `GlobalExceptionHandler` com códigos fixos; `NotificationStoreUnavailableIT` | closed |
| T-03-07 | Tampering | Injeção de linha de log via `eventType` | low | mitigate | `sanitizeForLog` troca `\r\n\t` e corta em 64 chars; corpo nunca logado (lacuna residual em outros caracteres de controle: 03-REVIEW WR-06) | closed |
| T-03-08 | Spoofing | Evento forjado direto na fila | low | accept | LocalStack em `127.0.0.1`, ambiente 100% local | closed |
| T-03-09 | Information Disclosure | `LOCALSTACK_AUTH_TOKEN` em logs (notification) | medium | mitigate | `LocalStackTestSupport.resolveAuthToken` só nomeia a variável | closed |
| T-03-10 | Tampering | Serviço criando fila e mascarando init hook | medium | mitigate | `queue-not-found-strategy: fail`; sem `createQueue`/`createTable` em `src/main` | closed |
| T-03-11 | Tampering | Evento de ajuste não commitado | medium | mitigate | Publicação no `InventoryController` após retorno de `setStock`; `InventoryService` sem mensageria | closed |
| T-03-12 | Repudiation | Evento perdido após commit (dual-write) | medium | accept | D-29/D-30; log ERROR com eventId/productId; README "Limitações conhecidas"; Outbox na Fase 5 | closed |
| T-03-13 | Information Disclosure | Atributo `JavaType` cruzando a fronteira | low | mitigate | `doNotSendPayloadTypeHeader()` | closed |
| T-03-14 | Denial of Service | SQS indisponível derrubando o PUT | medium | mitigate | Falha capturada no publicador; `StockEventPublishFailureIT` (latência sem timeout: 03-REVIEW WR-03) | closed |
| T-03-15 | Spoofing | Credencial AWS real / chamada à AWS real | low | mitigate | Credenciais literais `test`, endpoint sempre sobrescrito | closed |
| T-03-16 | Information Disclosure | `LOCALSTACK_AUTH_TOKEN` em logs (inventory) | medium | mitigate | Mesma resolução de token, só nomeia a variável | closed |
| T-03-20 | Spoofing | Rota do Gateway sem autenticação | high | mitigate | Gateway só roteia; serviço valida JWT; 401 sem token provado pelo gate e pelo smoke | closed |
| T-03-21 | Information Disclosure | Porta 8084 exposta | medium | mitigate | `127.0.0.1:8084:8084` | closed |
| T-03-22 | Information Disclosure | Fila/tabela legíveis via 4566 | low | accept | LocalStack em `127.0.0.1`; declarado no README | closed |
| T-03-23 | Tampering | Imagem com tag flutuante | medium | mitigate | `postgres:16.15`, `localstack/localstack:2026.08.3`; serviços de Dockerfile | closed |
| T-03-24 | Denial of Service | Serviço subindo antes de fila/tabela | medium | mitigate | Healthcheck do LocalStack checa fila+tabela; `depends_on: service_healthy` | closed |
| T-03-25 | Information Disclosure | Token impresso pelo smoke | low | mitigate | Token só em variável, nunca em `echo`/`fail` (visível na lista de processos: 03-REVIEW IN-06) | closed |
| T-03-26 | Information Disclosure | Credencial de demonstração | low | accept | Documentada na Fase 1 como dado de demo | closed |
| T-03-SC | Tampering | Cadeia de suprimentos (03-01/02/03) | low | accept | Dependências auditadas em 03-RESEARCH §Package Legitimacy Audit; 03-02/03-03 sem dependências novas | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-03-01 | T-03-08 | Ambiente local; em AWS real exigiria política de fila restringindo `SendMessage` ao produtor | Plano 03-01 (threat model) | 2026-09-22 |
| AR-03-02 | T-03-12 | Dual-write aceito até o Transactional Outbox da Fase 5 (D-29/D-30), perda observável em log ERROR | Usuário (D-29/D-30) | 2026-09-22 |
| AR-03-03 | T-03-22 | LocalStack só em `127.0.0.1`; sem ACL de fila nesta fase | Plano 03-03 (threat model) | 2026-09-22 |
| AR-03-04 | T-03-26 | Credencial de demonstração intencionalmente versionada (Fase 1) | Fase 1 | 2026-09-22 |
| AR-03-05 | T-03-SC | Dependências de group IDs oficiais, auditadas | Plano 03-01 (threat model) | 2026-09-22 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-09-24 | 24 | 23 | 1 (T-03-02 high — issuer não validado) | orchestrator (L1 grep) |
| 2026-09-24 | 24 | 24 | 0 | orchestrator (L1 grep) após quick 260923-tj9 |

## Security Audit 2026-09-24
| Metric | Count |
|--------|-------|
| Threats found | 24 |
| Closed | 24 |
| Open | 0 |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-09-24
