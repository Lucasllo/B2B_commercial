---
phase: "05"
slug: "saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o"
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: "2026-09-30"
---

# Phase 05 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Transação de negócio do order-service → `outbox_event` | O comando `ReserveStock`/`ReleaseStock` nasce atomicamente com a mudança de status do pedido | Ids de pedido/produto e quantidades |
| Relays (order e inventory) → SQS (LocalStack) | Rede; o envio pode falhar, demorar ou ser repetido (pelo menos uma vez) | Envelope da saga (`eventId`/`eventType`/`occurredAt` + campos do tipo) |
| Init hook do LocalStack → filas da saga | Único criador de `inventory-commands-queue`, `order-events-queue` e DLQs; serviços falham alto se a fila não existir | Atributos de fila (RedrivePolicy, `maxReceiveCount` 3) |
| `inventory-commands-queue` → `ReservationCommandListener` / `releaseAll` | Corpo vindo de fora do processo; no LocalStack qualquer processo com acesso à porta 4566 publica; `ReleaseStock` pode chegar antes/depois/junto do `ReserveStock` | Comandos de reserva/liberação |
| `order-events-queue` → `ReservationResultListener` | Corpo vindo de fora do processo, sem política de acesso no LocalStack | `StockReserved` / `StockReservationFailed` |
| `OrderSagaService` e `SagaTimeoutJob` → `orders` | Duas fontes de transição (resultado e timeout) sobre a mesma linha | Status, códigos e motivo de cancelamento |
| `setStock` → `outbox_event` → `notification-events-queue` | O evento de ajuste precisa sobreviver a um SQS indisponível | `STOCK_ADJUSTED` |
| `OrderResponse` → comprador/vendedor | Texto de cancelamento exibido pela API | `cancellationCode`, `cancellationReason` |
| Teste E2E → LocalStack (token Hobby) | Credencial pessoal lida do ambiente ou do `.env` | `LOCALSTACK_AUTH_TOKEN` |
| Dois contextos Spring → mesmo JVM e mesmo Postgres (e2e-tests) | Configuração e migrações de um serviço não podem vazar para o outro | Config, schemas Flyway |
| Scripts de smoke → stack local | Credencial de demonstração e tokens criados pelo script | Tokens (mantidos só em variáveis) |
| Documentação → avaliador | Afirmações sobre as garantias da saga | Texto do README/docs |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-05-01 | Tampering | `OrderService`/`OrderDecisionService` → SQS (dual-write) | high | mitigate | `OutboxWriter.enqueue` e `ReservationSagaStarter` com `Propagation.MANDATORY` na transação da mudança para RESERVING; único import de envio SQS de produção é o `OutboxRelay` (`OutboxWriter.java:31`, `ReservationSagaStarter.java:30,36-39`, `ReservationCommandPublishingIT`) | closed |
| T-05-02 | Denial of Service | `OutboxRelay` travado por evento que sempre falha | medium | mitigate | try/catch por evento com `recordFailure`, laço continua; ordenação por `attempts` (`OutboxRelay.java:54-66` order / `:60-75` inventory, `OutboxRelayTest`) | closed |
| T-05-03 | Information Disclosure | Payload do `ReserveStock` e log do relay | low | mitigate | Payloads só com ids/quantidades/motivo fixo; WARN do relay cita só `eventId`/`eventType`/fila; exceção do Spring Cloud AWS 3.4.2 não carrega payload (`ReserveStockCommand.java:23-29`, `OutboxRelay.java:61`) | closed |
| T-05-04 | Tampering | Fila criada implicitamente com atributos errados | low | mitigate | `queue-not-found-strategy: fail` nos dois serviços; init hook único criador com `maxReceiveCount` 3 (`02-create-order-saga-resources.sh:20-42`, `ReservationCommandPublishingIT.java:206-224`) | closed |
| T-05-05 | Repudiation | Pedido APPROVED legado sem comando depois do deploy | medium | mitigate | Migração de dados V2 move APPROVED → RESERVING com `ReserveStock` no outbox (`V2__order_reservation_saga.sql:60-88`, `OrderSagaMigrationIT`) | closed |
| T-05-SC | Tampering | Dependências Maven novas | high | mitigate | Só pacotes já em uso no reactor, versões pelos BOMs (`order-service/pom.xml:85`; `05-RESEARCH.md` Package Legitimacy Audit) | closed |
| T-05-06 | Tampering | `ReservationCommandListener`/`SagaCommandParser` — corpo forjado ou malformado | medium | mitigate | Teto 64 KB, `FAIL_ON_TRAILING_TOKENS`, itens 1..50, quantidade 1..1.000.000, `productId` repetido rejeitado, `reservationId == orderId`, log sanitizado (`SagaCommandParser.java`, `SagaCommandParserTest`) | closed |
| T-05-07 | Denial of Service | Mensagem venenosa em laço até a DLQ | medium | mitigate | Falha de negócio responde evento e consome; malformada descartada; só erro técnico reentrega, limitado a 3 recebimentos (`InventoryService.java:313-321`, `ReservationCommandConsumptionIT.java:212`) | closed |
| T-05-08 | Tampering | Sobrevenda sob comandos concorrentes | high | mitigate | `@Version` + `chk_inventory_not_oversold` + `@Retryable` fora de `@Transactional` no próprio `reserveAll`; linhas em ordem de `productId` (`InventoryService.java:250-258`, `IdempotentReservationIT.java:162`) | closed |
| T-05-09 | Tampering | Reserva parcial comprometida | high | mitigate | Avaliação de todas as linhas antes de qualquer escrita, numa transação (`InventoryService.java:299-330`, `ReservationCommandConsumptionIT.java:170,195`) | closed |
| T-05-10 | Repudiation | Reentrega do comando decrementando duas vezes | high | mitigate | `UNIQUE (product_id, reservation_id)` no livro; replay reemite `StockReserved` sem tocar estoque (`InventoryService.java:279-288`, `IdempotentReservationIT.java:97`) | closed |
| T-05-11 | Information Disclosure | Quantidade disponível no evento de falha, visível ao comprador | low | accept | Ver Accepted Risks Log AR-05-01 | closed |
| T-05-12 | Spoofing | `StockReserved` forjado confirmando pedido | medium | mitigate | Só transiciona a partir de RESERVING e com itens idênticos aos do pedido; WR-02 (cd97c8a) passou a checar teto antes do cast e rejeitar `productId` repetido (`OrderSagaService.java:101-104,160-166`, `SagaEventParser.java:97-103,164-179`, `ReservationResultListenerIT.java:353`) | closed |
| T-05-13 | Tampering | Corrida entre resultado e timeout sobre o mesmo pedido | high | mitigate | `findByIdForUpdate` (`PESSIMISTIC_WRITE`) nas três transições, com guarda por estado (`OrderRepository.java:26-28`, `OrderSagaService.java:59,95,139`) | closed |
| T-05-14 | Information Disclosure | `cancellationReason` ecoando exceção ou texto da mensagem | low | mitigate | Modelo fixo em `CancellationReasons` com `sku` do snapshot, truncado em 500; teste sem `Exception`/`at com.orderflow`/`http://` (`CancellationReasons.java:35-71`, `ReservationResultListenerIT.java:160,257`) | closed |
| T-05-15 | Denial of Service | Mensagem venenosa no listener do order-service | medium | mitigate | Teto 64 KB, parse estrito, allow-list de `reasonCode`, descarte com log sanitizado; só erro técnico reentrega, limitado pela DLQ (`SagaEventParser.java`, `ReservationResultListener.java:41-50`, `ReservationResultListenerIT.java:223`) | closed |
| T-05-16 | Repudiation | Estoque reservado órfão para pedido cancelado | high | mitigate | `ReleaseStock` (LATE_RESERVATION) no outbox na mesma transação travada que lê o pedido CANCELLED (`OrderSagaService.java:111-119`, `ReservationResultListenerIT.java:309`, `SagaTimeoutIT.java:178`) | closed |
| T-05-17 | Tampering | Corrida timeout × resultado no mesmo pedido | high | mitigate | `expireReservation` usa a mesma trava e reavalia status e prazo sob ela (`OrderSagaService.java:139-148`, `SagaTimeoutIT.java:155,178`) | closed |
| T-05-18 | Denial of Service | Pedido preso em RESERVING consumindo crédito para sempre | high | mitigate | `SagaTimeoutJob` `@Scheduled` com prazo configurável, cancelamento + `ReleaseStock` na mesma transação; WR-04 (83eefaf) deu pool de 2 threads para o relay não esfomear o job (`SagaTimeoutJob.java:53-65`, `application.yml:14,123-125`, `SagaTimeoutIT.java:122`) | closed |
| T-05-19 | Tampering | Estoque preso por `ReleaseStock` chegando antes do `ReserveStock` | high | mitigate | Lápide no livro e `RESERVATION_CANCELLED` no `ReserveStock` tardio; FK removida (`InventoryService.java:266-278,422-424`, `V3__allow_reservation_tombstones.sql:71`, `TombstoneReleaseIT.java:156,189`) | closed |
| T-05-20 | Repudiation | Evento de ajuste perdido por falha do SQS (D-30) | medium | mitigate | `STOCK_ADJUSTED` gravado no outbox na transação do `setStock`; publicador direto removido (`InventoryService.java:103-125`, `OutboxRelayTest.java:46,72`, `StockAdjustedEventPublishingIT.java:82`) | closed |
| T-05-21 | Tampering | Lápide órfã acumulando no livro sem FK | low | accept | Ver Accepted Risks Log AR-05-02 | closed |
| T-05-22 | Information Disclosure | `LOCALSTACK_AUTH_TOKEN` em log ou erro do E2E | medium | mitigate | `resolveAuthToken()` nunca imprime o valor; erro cita só o nome da variável (`e2e-tests/.../LocalStackTestSupport.java:73-88,113-115`) | closed |
| T-05-23 | Tampering | Contexto de um serviço rodando migrações/config do outro | medium | mitigate | `spring.config.location` com o arquivo real de cada serviço e Flyway por `filesystem:`; smoke de contextos (`E2eInfrastructure.java:111-112,128-129`, `E2eContextsSmokeIT`) | closed |
| T-05-24 | Repudiation | E2E verde por atalho escondendo saga quebrada | medium | mitigate | Sem dublês em `e2e-tests/src`; `JdbcTemplate` só lê; resultado só pelas filas; o único envio direto é o replay planejado de comando após CONFIRMED (`05-05-PLAN.md:71`, `OrderReservationSagaE2EIT.java:247-270`) | closed |
| T-05-25 | Information Disclosure | Token impresso na saída dos smokes | medium | mitigate | Tokens só em variáveis, nunca ecoados, sem `set -x` (`scripts/smoke-order-saga.sh:105-107,124-126`) | closed |
| T-05-26 | Repudiation | Documentação prometendo garantia que o código não dá | low | mitigate | README afirma "pelo menos uma vez, nunca exatamente uma vez ou em ordem garantida" (`README.md:326`); checagem humana do texto aprovada na UAT (teste 4 de `05-UAT.md`) | closed |
| T-05-27 | Tampering | Filas da saga sem política de acesso no LocalStack | low | accept | Ver Accepted Risks Log AR-05-03 | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-05-01 | T-05-11 | O motivo de cancelamento mostra "disponível N, solicitado M" por decisão explícita (D-56, `05-CONTEXT.md:31`); só números e ids, nenhum dado de outra empresa. Documentado em `README.md:487` (Limitações Fase 5, item 5) | Lucas Lopes (D-56) | 2026-09-30 |
| AR-05-02 | T-05-21 | Lápides são inertes (nunca decrementam estoque) e só surgem de `ReleaseStock`; limpeza fica para uma retenção futura. Documentado em `README.md:494` (item 7) e no comentário da V3 | Lucas Lopes (plano 05-04) | 2026-09-30 |
| AR-05-03 | T-05-27 | Ambiente local de demonstração: no LocalStack qualquer processo com acesso à porta 4566 publica nas filas. Em AWS real, `SendMessage` seria restrito por política de fila/IAM. Documentado em `README.md:435-438,491-493`. Cobre também o risco residual de um `StockReservationFailed` forjado cancelar um pedido RESERVING | Lucas Lopes (plano 05-06) | 2026-09-30 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-09-30 | 28 | 28 | 0 | gsd-security-auditor (ASVS L1, block_on high) |

Notas da auditoria:
- As correções do review WR-02 (cd97c8a) e WR-04 (83eefaf) reforçam T-05-12/T-05-15 e T-05-18; WR-01 (4226a87) muda só diagnóstico, sem alterar a mitigação de T-05-07; WR-03 (não corrigido) não mapeia para nenhuma ameaça (custo/latência do long polling).
- Nenhum SUMMARY da fase tem seção `## Threat Flags`; o IN-02 do review (contexto de teste consumindo a fila) é só infraestrutura de teste.

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-09-30
