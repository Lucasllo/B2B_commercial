# Phase 7: Endurecimento, Observabilidade e Entrega - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-10-01
**Phase:** 07-endurecimento-observabilidade-e-entrega
**Areas discussed:** Swagger pelo Gateway, Correlation-ID ponta a ponta, Pipeline de CI, Lacunas de teste e ADRs

---

## Swagger pelo Gateway

| Option | Description | Selected |
|--------|-------------|----------|
| UI única no Gateway | Swagger UI no Gateway com dropdown para os 5 serviços | ✓ |
| Uma UI por serviço via Gateway | Rotas /api/<svc>/swagger-ui.html; cinco links | |
| Ambos | Agregada + individuais | |

| Option | Description | Selected |
|--------|-------------|----------|
| Contratos + erros | @Tag/@Operation, @Schema com exemplos, OrderStatus, erros 401/403/404/409/503 | ✓ |
| Mínimo | Só @Tag/@Operation | |
| Completo + diagrama | Recomendado + ciclo de vida no info | |

| Option | Description | Selected |
|--------|-------------|----------|
| Sim, bearer JWT | SecurityScheme bearerAuth, botão Authorize | ✓ |
| Só leitura | Sem esquema de segurança | |

| Option | Description | Selected |
|--------|-------------|----------|
| Pelo Gateway | server = http://localhost:8080/api | ✓ |
| Direto no serviço | Porta do serviço | |

| Option | Description | Selected |
|--------|-------------|----------|
| Públicos | permitAll só em /v3/api-docs/** e /swagger-ui/** | ✓ |
| Protegidos | Exigem JWT | |

**User's choice:** todas as opções recomendadas.

---

## Correlation-ID ponta a ponta

| Option | Description | Selected |
|--------|-------------|----------|
| Coluna no outbox + atributo SQS | correlation_id no outbox, MessageAttribute, envelope inalterado | ✓ |
| Campo no envelope JSON | correlationId no payload; muda SAGA_MESSAGE_CONTRACT | |

| Option | Description | Selected |
|--------|-------------|----------|
| X-Correlation-Id, reaproveita se válido | Gera se ausente; valida formato; devolve na resposta | ✓ |
| Sempre gera no Gateway | Ignora header do cliente | |

| Option | Description | Selected |
|--------|-------------|----------|
| Texto com MDC no pattern | [correlationId] em cada linha | ✓ |
| JSON estruturado | Structured logging do Boot | |
| Texto local, JSON por profile | Os dois | |

| Option | Description | Selected |
|--------|-------------|----------|
| Herdam o ID do pedido | Timeout da saga reaproveita o correlation_id original | ✓ |
| Geram ID novo | ID por execução do job | |

| Option | Description | Selected |
|--------|-------------|----------|
| Smoke + teste automatizado | smoke-correlation-id.sh + IT/E2E | ✓ |
| Só smoke | | |
| Só testes automatizados | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Sim, interceptor no RestClient | order → catalog/auth propagam o header | ✓ |
| Não | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Correlation-ID próprio | Filtro + MDC + atributo SQS manuais; tracing no ADR | ✓ |
| Micrometer Tracing | W3C traceparent automático | |

**User's choice:** todas as opções recomendadas.

---

## Pipeline de CI

| Option | Description | Selected |
|--------|-------------|----------|
| Matriz por serviço + job E2E | Job por módulo + e2e-tests | ✓ |
| Job único ./mvnw verify | | |
| Matriz + paths filter | | |

| Option | Description | Selected |
|--------|-------------|----------|
| push em qualquer branch + pull_request | | ✓ |
| push só em main/master + PR | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Falhar visível | Quebra com mensagem clara sem token | ✓ |
| Pular os ITs com LocalStack | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Relatório de testes | Resultados surefire/failsafe no run | ✓ |
| Badge no README | | |
| Build das imagens Docker | | |
| Relatório JaCoCo | | |

Follow-up (conflito com a sessão LocalStack Hobby única por token, registrada no STATE.md):

| Option | Description | Selected |
|--------|-------------|----------|
| Matriz com LocalStack serializado | Postgres-only em paralelo; LocalStack em sequência; pesquisador confirma | ✓ |
| Job único sequencial | | |
| Pesquisador decide | | |

**Notes:** A primeira resposta da pergunta de extras veio vazia; foi refeita e o usuário escolheu só o relatório de testes.

---

## Lacunas de teste e ADRs

| Option | Description | Selected |
|--------|-------------|----------|
| Matriz regra → teste | Auditoria por serviço, sem limite numérico | ✓ |
| JaCoCo com limite mínimo | | |
| Matriz + JaCoCo só relatório | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Unit do filtro + IT de rotas | | ✓ |
| Só unit do filtro | | |

| Option | Description | Selected |
|--------|-------------|----------|
| MADR em docs/adr/ | Contexto, Decisão, Alternativas, Consequências; índice | ✓ |
| Nygard clássico | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Obrigatórios + principais da fase | 4 + ~5 | ✓ |
| Só os 4 obrigatórios | | |
| Um por Key Decision | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Resilience4j fica como backlog | | ✓ |
| Sim, circuit breaker + retry | | |

| Option | Description | Selected |
|--------|-------------|----------|
| Aceito, com a fase de origem | Links para D-xx e código | ✓ |
| Aceito, data de hoje | | |

| Option | Description | Selected |
|--------|-------------|----------|
| README com seção "Para avaliadores" | | ✓ |
| Só atualizar links | | |

| Option | Description | Selected |
|--------|-------------|----------|
| WR-01..03 da Fase 6 entram | Cada correção com teste | ✓ |
| Ficam como backlog | | |

---

## Claude's Discretion

- Regex do header e chave do MDC; filtro duplicado vs módulo compartilhado; ID usado por /approve, /ship e /deliver; nome do MessageAttribute; ação de relatório de testes; numeração e lista final de ADRs; organização das rotas de docs no Gateway; arquivos em estudos/.

## Deferred Ideas

- Resilience4j; Micrometer Tracing/OpenTelemetry; logs JSON; JaCoCo com limite; badge/build de imagens no CI; WR-03 da Fase 5 (poll-timeout); retenção do outbox e limpeza de lápides.
