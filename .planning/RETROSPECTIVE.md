# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v1 — MVP

**Shipped:** 2026-11-07
**Phases:** 7 | **Plans:** 40 | **Sessions:** não rastreado

### What Was Built
- Seis serviços Spring Boot 3.5 / Java 21 (gateway, auth, catalog, inventory, order, notification) subindo com um `docker compose up` junto de PostgreSQL e LocalStack (SQS + DynamoDB)
- Autenticação JWT RS256 auto-emitida e validada localmente via JWKS, com isolamento por empresa provado por teste adversarial
- Reserva de estoque atômica (lock otimista + Spring Retry) provada sob 20 contendores simultâneos
- Aprovação por limite de crédito com exposição acumulada serializada por empresa (`PESSIMISTIC_WRITE`)
- Saga de reserva orquestrada pelo order-service com Transactional Outbox nos dois lados, consumidores idempotentes, timeout com compensação e lápides contra reordenação do SQS — sempre termina em CONFIRMED ou CANCELLED
- Ciclo de vida até SHIPPED/DELIVERED com transportadora simulada, rastreio S10 e linha do tempo no DynamoDB
- OpenAPI agregado no Gateway, Correlation-ID nos seis serviços, CI no GitHub Actions com E2E, matriz regra→teste verificada por script e 11 ADRs em português

### What Worked
- Fatias verticais demonstráveis a cada fase: sempre havia algo novo para exercitar por REST no compose, o que deixou os UATs concretos
- Ordem de de-risking: provar o encanamento SQS→DynamoDB no cenário mais simples (Fase 3) antes da saga; isolar a saga numa fase própria (Fase 5) — a saga fechou sem retrabalho estrutural
- Smoke script por fase na stack real (`scripts/smoke-*.sh`) — pegou problemas que os ITs com Testcontainers não pegavam (healthcheck, rotas do Gateway, ordem de subida)
- Evidência gravada em arquivo para cada verificação humana (07-SMOKE/SWAGGER/CI-FAILURE-EVIDENCE.txt) — transformou "confia em mim" em prova auditável
- Guardas baratas no CI (ADRs, matriz de cobertura, testes pulados) mantiveram a documentação honesta

### What Was Inefficient
- Fase 7 cresceu para 11 planos e ficou ~5 semanas aberta entre o último plano (2026-10-03) e o fechamento (2026-11-07) — endurecimento transversal é maior do que parece quando cobre seis serviços
- Gates por fase pulados: `06-SECURITY.md` nunca foi gerado e as VALIDATION das fases 1 e 6 ficaram em `draft` — viraram override no fechamento em vez de serem resolvidos no momento barato
- ~28 warnings de code review acumulados nas fases 2–5 e 7; só os da Fase 6 foram corrigidos (na Fase 7)
- Digests das VERIFICATION das fases 1–6 ficaram `stale` porque fases posteriores editaram arquivos cobertos — a verificação por digest não distingue edição legítima de regressão
- Sessão única do LocalStack Hobby por token: Testcontainers falha com o compose de pé, e o CI precisou rodar LocalStack em sequência
- A auditoria do milestone listou como abertos warnings que a Fase 7 já tinha corrigido — o REVIEW.md não é atualizado quando um fix chega em outra fase

### Patterns Established
- Transactional Outbox com relay `@Scheduled` + `SKIP LOCKED` como único caminho de publicação SQS
- Envelope de mensagem plano (`eventId`/`eventType`/`occurredAt`) e `reservationId = orderId` para idempotência natural
- Correlation-ID validado por regex nas fronteiras HTTP e SQS, carregado no outbox numa coluna própria
- `DownstreamStubServer` (JDK pura, socket real) para stubs de vizinhos nos testes, exercitando timeouts reais
- Tabela única de transições em `OrderStatus` consultada por domínio, saga e teste do diagrama Mermaid
- Um smoke script por fase + arquivo de evidência por verificação humana

### Key Lessons
1. Rodar `/gsd-secure-phase` e `/gsd-validate-phase` logo após cada fase — no fechamento do milestone eles viram dívida de processo
2. Corrigir warnings de code review dentro da fase (`/gsd-code-review N --fix`) ou registrar explicitamente onde foram corrigidos, para a auditoria não contar duas vezes
3. Testes de contrato entre serviços precisam entrar cedo; stubs manuais divergem silenciosamente do provedor real
4. Fase de "endurecimento" transversal deve ser dividida por eixo (docs, observabilidade, testes, CI) em vez de um único bloco grande
5. Definir no `docker-compose` `start_period` generoso desde o início — partida a frio com LocalStack é lenta e intermitente

### Cost Observations
- Model mix: não rastreado
- Sessions: não rastreado
- Notable: 314 commits em 52 dias; ~21 mil linhas de teste para ~10 mil de produção (razão ~2:1)

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1 | — | 7 | Fatias verticais em modo `mvp`; saga isolada numa fase própria; evidência gravada para verificações humanas |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v1 | 72 regras mapeadas na matriz regra→teste, 7 módulos | não medido (matriz por regra, não por linha) | — |

### Top Lessons (Verified Across Milestones)

1. (a confirmar no próximo milestone) Rodar gates de segurança/validação por fase, não no fechamento
2. (a confirmar no próximo milestone) Testes de contrato cedo
