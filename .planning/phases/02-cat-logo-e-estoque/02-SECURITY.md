---
phase: "02"
slug: "cat-logo-e-estoque"
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: "2026-09-20"
---

# Phase 02 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| host network → API Gateway (porta 8080) | Único ponto de entrada publicado sem restrição a loopback | Todo tráfego externo, incluindo JWT em `Authorization` |
| API Gateway → catalog-service / inventory-service | Gateway apenas roteia (D-05); cada destino valida o JWT localmente | Header `Authorization` repassado; body de produto/estoque |
| requisições concorrentes → mesma linha de inventário | Vários writers simultâneos sobre a invariante de não-sobrevenda | Nenhum dado cruza um boundary de rede aqui — é o ponto de contenção interno que a fase existe para proteger |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-02-01 | Elevation of Privilege | `POST`/`PUT /products`, `PUT /products/{id}/status` | high | mitigate | `@PreAuthorize("hasRole('SELLER_ADMIN')")` nos três verbos + `@EnableMethodSecurity`; `ProductControllerIT` cobre 403/401 | closed |
| T-02-02 | Elevation of Privilege | mass assignment do campo `status` na criação de produto | high | mitigate | `CreateProductRequest` sem campo de status; construtor de `Product` fixa `ACTIVE` por literal | closed |
| T-02-03 | Spoofing | token forjado/expirado/outra chave aceito pelo catalog-service | high | mitigate | OAuth2 Resource Server + `jwk-set-uri` + RS256; testes adversariais 401 (ver Nota 1 — issuer validation não está no config de produção) | closed |
| T-02-04 | Tampering | preço alterado em silêncio na escrita | medium | mitigate | `@Digits`/`@DecimalMin` nos DTOs; `NUMERIC(19,2) CHECK (price >= 0)`; sem `setScale`/`round` | closed |
| T-02-05 | Tampering | status gravado fora do conjunto válido | medium | mitigate | `CHECK (status IN (...))` + `@Enumerated(EnumType.STRING)` | closed |
| T-02-06 | Information Disclosure | comprador enxergando produto descontinuado | low | mitigate | Filtro por `Authentication`, nunca por parâmetro do cliente | closed |
| T-02-07 | Information Disclosure | corpo de erro vazando stack trace/SQL/constraint | medium | mitigate | `GlobalExceptionHandler` com chaves fixas; teste asserta ausência de `Exception`/`at com.orderflow` | closed |
| T-02-08 | Denial of Service | página sem teto em `GET /products` | low | mitigate | `max-page-size: 100`, `default-page-size: 20` | closed |
| T-02-09 | Repudiation | ausência de registro de quem alterou preço/status | low | accept | Ver Accepted Risks Log | closed |
| T-02-SC-01 | Tampering | cadeia de suprimentos (catalog-service) | low | accept | Ver Accepted Risks Log | closed |
| T-02-10 | Tampering | venda acima do estoque por condição de corrida | high | mitigate | `@Version` + `saveAndFlush` por tentativa + reexecução com transação nova + `chk_inventory_not_oversold`; provado por `StockReservationConcurrencyIT` | closed |
| T-02-11 | Repudiation | replay de requisição de reserva duplicando gasto de estoque | high | mitigate | `UNIQUE(product_id, reservation_id)` + checagem antecipada; violação de integridade é reexecutável | closed |
| T-02-12 | Elevation of Privilege | qualquer autenticado definindo estoque/reservando/liberando | high | mitigate | `@PreAuthorize("hasRole('SELLER_ADMIN')")` nos três métodos de escrita | closed |
| T-02-13 | Spoofing | token forjado/expirado/outra chave aceito pelo inventory-service | high | mitigate | Mesmo setup do catalog-service (ver Nota 1) | closed |
| T-02-14 | Tampering | quantidade negativa/zero corrompendo saldo | medium | mitigate | `@PositiveOrZero`/`@Positive` + `CHECK` no banco | closed |
| T-02-15 | Denial of Service | `reservationId` sem limite enchendo o livro de reservas | low | mitigate | `@Size(max=255)` + `VARCHAR(255)` | closed |
| T-02-16 | Denial of Service | estoque travado indefinidamente por reservas órfãs | low | accept | Ver Accepted Risks Log | closed |
| T-02-17 | Information Disclosure | corpo de erro vazando nome de constraint/SQL/stack trace (inventory) | medium | mitigate | Handler global com chaves fixas (ver Nota 2 — teste de ausência de vazamento existe no catalog-service mas não foi replicado no inventory-service) | closed |
| T-02-18 | Repudiation | ausência de registro de quem reservou/liberou | low | accept | Ver Accepted Risks Log | closed |
| T-02-SC-02 | Tampering | cadeia de suprimentos (spring-retry, spring-boot-starter-aop) | medium | mitigate | Auditado manualmente contra Maven Central, group IDs oficiais Spring, resolvidos por BOM sem tag de versão | closed |
| T-02-20 | Spoofing | rota do Gateway encaminhando tráfego sem autenticação | high | mitigate | Gateway só roteia (D-05); cada destino exige JWT; 401 confirmado nas duas rotas (ver Nota 3 — verificação manual, não é gate automatizado repetível) | closed |
| T-02-21 | Tampering | venda acima do estoque sob concorrência HTTP real | high | mitigate | `StockReservationConcurrencyIT` — barreira, threads virtuais, socket real; nunca excede o estoque em três cenários | closed |
| T-02-22 | Tampering | reexecução por conflito de versão anulada pela ordem dos aspectos | high | mitigate | `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE)` + `InventoryRetryContentionIT` prova reexecução real via `RetryListener` de teste | closed |
| T-02-23 | Information Disclosure | portas dos serviços novos expostas além do host | medium | mitigate | Publicação restrita a `127.0.0.1`, mesma convenção do `auth-service`/`postgres` | closed |
| T-02-24 | Tampering | divergência silenciosa por imagem com tag flutuante | medium | mitigate | Nenhuma imagem nova; Dockerfiles com tag de patch fixa; nenhuma tag flutuante no compose | closed |
| T-02-25 | Denial of Service | subida bloqueada por dependência desnecessária do LocalStack | low | mitigate | Nenhum dos dois serviços novos depende de `localstack` | closed |
| T-02-26 | Information Disclosure | credencial de demonstração do README reutilizada fora do projeto | low | accept | Ver Accepted Risks Log | closed |
| T-02-SC-03 | Tampering | cadeia de suprimentos (teste de concorrência) | low | accept | Ver Accepted Risks Log | closed |

*Status: open · closed · open — below `high` threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above `workflow.security_block_on` (`high`) count toward `threats_open`*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

**threats_open: 0** — nenhuma ameaça `high`+ está aberta. Todos os `accept` abaixo estão fechados por entrada no Accepted Risks Log (nenhuma mudança de implementação necessária).

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-02-01 | T-02-09 | Sem campo de autoria em preço/status; domínio de vendedor único (marketplace multi-vendedor fora de escopo). Auditoria mais rica entra com Correlation-ID na Fase 7 (QUAL-02). | Plano 02-01 (Claude's Discretion, `02-CONTEXT.md`) | 2026-09-18 |
| AR-02-02 | T-02-16 | D-13: sem expiração automática de reserva nesta fase — cenário real de timeout/falha de rede só aparece com SQS na Fase 5. Liberação explícita e idempotente já existe. | Plano 02-02 (`02-CONTEXT.md` D-13) | 2026-09-19 |
| AR-02-03 | T-02-18 | Livro de reservas registra identificador do chamador, quantidade e timestamps — suficiente para reconstruir o que aconteceu nesta fase. Atribuição a ator nomeado depende de identidade de serviço da Fase 5; rastreabilidade por Correlation-ID entra na Fase 7. | Plano 02-02 (`02-CONTEXT.md`) | 2026-09-19 |
| AR-02-04 | T-02-26 | Credencial `admin@orderflow.local` é dado de demonstração intencionalmente versionado (documentado em `README.md` §Credenciais de demonstração desde a Fase 1) — não é credencial administrativa real. | Fase 1 (`01-SKELETON.md`) | 2026-09-18 |
| AR-02-05 | T-02-SC-01 | `catalog-service` não introduz dependência nova — replica exatamente o conjunto já auditado do `auth-service`, resolvido pelos BOMs oficiais. | Plano 02-01 (`02-RESEARCH.md` §Package Legitimacy Audit) | 2026-09-18 |
| AR-02-06 | T-02-SC-03 | O disparador de concorrência do plano 02-03 usa apenas biblioteca padrão do Java 21 (`java.net.http.HttpClient`, `Executors.newVirtualThreadPerTaskExecutor()`) — nenhum install de `npm`/`pip`/`cargo`/Maven novo. | Plano 02-03 | 2026-09-20 |

*Accepted risks do not resurface in future audit runs.*

---

## Non-Blocking Findings (from Security Audit 2026-09-20)

Achados do `gsd-security-auditor` que não abrem nenhuma ameaça (nenhum é `high`+ nem contradiz a mitigação registrada), mas registram lacunas entre o que o registro declara e o que está implementado — vale corrigir num plano futuro, não bloqueia a fase:

1. **Issuer validation ausente na configuração de produção (T-02-03, T-02-13).** Os registros de ameaça declaram "RS256 e validação de emissor", mas nenhum `application.yml` de produção (`catalog-service`/`inventory-service`) declara `issuer-uri`; só `jwk-set-uri` é injetado pelo compose. A validação de `iss` só existe no decoder de teste (`TestJwt.java`, via `JwtValidators.createDefaultWithIssuer`). Risco residual é baixo hoje (uma única chave/emissor no sistema), mas o controle nomeado no registro não está em produção. Corrigir adicionando `issuer-uri` (ou um validador de `issuer` explícito) aos dois `application.yml` de produção.
2. **T-02-17 sem teste de ausência de vazamento no inventory-service.** O catalog-service tem `ProductControllerIT` asserindo ausência de `Exception`/`at com.orderflow` no corpo de erro; o inventory-service não tem o equivalente — só asserta a presença da chave `error`. O controle de código (handler uniforme, sem texto bruto do banco) existe e é a proteção real, mas nada guarda contra uma regressão futura no handler. Adicionar a mesma asserção negativa a `InventoryControllerIT`.
3. **T-02-20 verificado manualmente, sem gate automatizado repetível.** Os checks de 401 sem token pelo Gateway foram executados pelo orquestrador diretamente no host (Docker Desktop e `.env` indisponíveis dentro do worktree isolado do executor) — ver `02-VALIDATION.md` §Manual-Only Verifications. A evidência de código é sólida, mas uma futura regressão de rota no Gateway não teria um teste automatizado para pegá-la. Considerar um teste de contrato leve (ex.: Spring Cloud Contract, já no stack) para essa checagem no futuro.

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-09-20 | 28 | 28 (22 mitigate verified + 6 accept logged) | 0 | gsd-security-auditor (opus) + orchestrator (accepted-risks log write) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-09-20
