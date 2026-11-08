---
phase: "07"
slug: "endurecimento-observabilidade-e-entrega"
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: "2026-11-08"
---

# Phase 07 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| cliente → Gateway | `X-Correlation-Id` e o resto da requisição vêm de fora, sem autenticação no Gateway (D-05) | ver descrição |
| Gateway → serviços | o Gateway reescreve o header antes de encaminhar | ver descrição |
| serviço → Gateway (resposta) | um serviço poderia devolver outro `X-Correlation-Id` | ver descrição |
| Gateway/cliente → order-service | `X-Correlation-Id` pode vir de chamada direta à porta 8085 | ver descrição |
| `order-events-queue` → `ReservationResultListener` | atributo `correlationId` vem de fora do processo | ver descrição |
| order-service → catalog/auth | header de saída copiado do MDC | ver descrição |
| navegador → `/v3/api-docs` (sem token) | spec público por desenho (D-91) | ver descrição |
| navegador → endpoints de negócio | continuam exigindo JWT em cada serviço | ver descrição |
| `inventory-commands-queue` → `ReservationCommandListener` | atributo `correlationId` vem de fora do processo | ver descrição |
| cliente/Gateway → inventory (HTTP) | `X-Correlation-Id` pode vir de chamada direta à porta 8083 | ver descrição |
| order-service/Gateway → catalog/auth | `X-Correlation-Id` recebido de outro serviço ou de chamada direta | ver descrição |
| cliente → `POST /auth/login` | credenciais trafegam no corpo | ver descrição |
| repositório público → leitor externo | ADRs são documentação publicada | ver descrição |
| `notification-events-queue` → `NotificationEventListener` | corpo e atributo vêm de fora do processo; fila sem DLQ | ver descrição |
| navegador → spec público | D-91 | ver descrição |
| navegador → endpoints de pedido/estoque | exigem JWT e papel | ver descrição |
| `inventory-commands-queue` → listener | comando pode vir corrompido ou forjado | ver descrição |
| matriz publicada → avaliador | a matriz é afirmação de cobertura que precisa ser verdadeira | ver descrição |
| código de PR (inclusive de fork) → runner do GitHub | código não confiável roda no CI | ver descrição |
| secret do repositório → job | o token Hobby só pode chegar a jobs confiáveis | ver descrição |
| ações de terceiros → pipeline | supply-chain do workflow | ver descrição |
| smoke → stack local | script lê respostas com tokens | ver descrição |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-07-01 | Tampering | `CorrelationIdFilter` — log injection por `X-Correlation-Id` com CR/LF ou delimitadores | high | mitigate | Regex `[A-Za-z0-9-]{1,64}`; valor inválido vira UUID novo antes do MDC; casos CR/LF, espaço, 65 chars e vazio em `CorrelationIdFilterTest` | closed |
| T-07-02 | Repudiation | header duplicado/ambíguo no pedido encaminhado ou na resposta | medium | mitigate | `TreeSet` case-insensitive em `getHeaderNames`; wrapper de resposta ignora o header vindo do serviço; IT com stub que ecoa | closed |
| T-07-03 | Information Disclosure | linha de acesso do Gateway | medium | mitigate | Só método, `getRequestURI()` (sem query) e status; nada de headers ou corpo; `/actuator` fora do log | closed |
| T-07-04 | Information Disclosure | Swagger UI e specs públicos no Gateway publicado em `0.0.0.0:8080` | low | accept | Risco aceito (README.md:755-757; portas dos serviços em 127.0.0.1) | closed |
| T-07-05 | Denial of Service | header gigante | low | accept | Risco aceito (regex limita a 64 chars o que chega ao MDC; limite de header padrão do Tomcat) | closed |
| T-07-06 | Tampering | `CorrelationContext.open` — log injection por header ou atributo SQS | high | mitigate | Mesmo regex do Gateway no header e no atributo; inválido vira UUID; testes com CR/LF e 65 chars | closed |
| T-07-07 | Information Disclosure | linhas INFO novas (filtro, relay, listener, saga) | medium | mitigate | Só ids, tipo de evento, fila, método/caminho/status; nunca payload, `Authorization` ou query (T-05-03) | closed |
| T-07-08 | Tampering | atomicidade outbox ↔ ID | low | mitigate | `OutboxWriter` grava o ID na mesma transação (`MANDATORY`); o relay lê da linha, nunca do MDC | closed |
| T-07-09 | Spoofing | atributo forjado por quem posta na fila | low | accept | Risco aceito (README.md:743-746; ADR 0008) | closed |
| T-07-10 | Denial of Service | valor de ID maior que a coluna | low | mitigate | Regex limita a 64 = tamanho da coluna | closed |
| T-07-11 | Elevation of Privilege | `SecurityConfig` ampliado para o Try it out funcionar | high | mitigate | Plano não toca `SecurityConfig` (contagem de `permitAll` igual a 1 no critério de aceite); `businessEndpointStillRequiresTokenGuardAgainstRegression` continua verde | closed |
| T-07-12 | Information Disclosure | exemplos `@Schema` com segredo/JWT real | medium | mitigate | Só credenciais de demonstração já publicadas no README e dados fictícios; JWT de exemplo truncado | closed |
| T-07-13 | Information Disclosure | spec expõe a superfície da API sem token | low | accept | Risco aceito (README.md:259-261) | closed |
| T-07-14 | Repudiation | documentação diverge do comportamento (erro/enum inventado) | low | mitigate | `OpenApiDocsIT` compara enum com `ProductStatus.values()` e exige respostas de erro por operação | closed |
| T-07-15 | Tampering | atributo `correlationId` forjado com CR/LF ou delimitadores (log injection) | high | mitigate | `CorrelationContext.open` valida o regex; inválido vira UUID; caso `bad value!` em `ReservationCommandConsumptionIT` confere que o valor não chega ao log | closed |
| T-07-16 | Information Disclosure | INFO novos no listener e no relay | medium | mitigate | Só fila, `eventId`, `eventType`; nunca payload (T-05-03) | closed |
| T-07-17 | Tampering | ID mudando a decisão de reserva | low | mitigate | ID só no MDC/coluna/atributo; envelope inalterado; suíte `ReservationCommandConsumptionIT` existente continua verde | closed |
| T-07-18 | Tampering | log injection pelo header nos filtros de catalog/auth | high | mitigate | Mesmo regex/geração de UUID; casos de CR/LF e 65 chars no `CorrelationIdFilterTest` | closed |
| T-07-19 | Information Disclosure | linha de acesso do login | high | mitigate | Só método, caminho e status; `CorrelationIdLoggingIT` do auth prova que e-mail, senha e token não aparecem na saída | closed |
| T-07-20 | Repudiation | testes unitários "de fachada" | low | mitigate | Cada caso afirma a regra (captor/estado/exceção); matriz de 07-10 confere a existência dos arquivos | closed |
| T-07-21 | Information Disclosure | ADR citando token, `.env` ou credencial | low | mitigate | Nenhum valor de segredo é lido ou citado; o ADR 0003 descreve a exigência do token sem valor | closed |
| T-07-22 | Repudiation | ADR com D-xx ou fase inventados | low | mitigate | `check-adrs.sh` confere cada D-xx citado contra os CONTEXT.md e cada link relativo | closed |
| T-07-23 | Tampering | atributo `correlationId` forjado (log injection) | high | mitigate | `CorrelationContext.open` valida; caso `bad value!` em `CorrelationIdConsumptionIT` | closed |
| T-07-24 | Tampering | `orderId`/`eventType` do evento inválido levados ao WARN (log injection) | medium | mitigate | `orderId` só passa se casar UUID (senão `?`); `eventType` por `sanitizeForLog`; caso CR/LF no `NotificationServiceTest` | closed |
| T-07-25 | Information Disclosure | payload do evento em log | medium | mitigate | INFO e WARN só com `eventType`/`entityId`/`orderId`; nunca `rawPayload` | closed |
| T-07-26 | Elevation of Privilege | `SecurityConfig` alterado para a Swagger UI | high | mitigate | Não alterado (critério de aceite); guarda de regressão do `OpenApiDocsIT` | closed |
| T-07-27 | Elevation of Privilege | `SecurityConfig` de order/inventory ampliado | high | mitigate | Não alterado (contagem de `permitAll` igual a 1 no critério de aceite); guardas de regressão dos `OpenApiDocsIT` | closed |
| T-07-28 | Repudiation | spec divergente (enum/erros) | low | mitigate | Enum comparado com `OrderStatus.values()`; contagem de operações e erros por operação no teste | closed |
| T-07-29 | Information Disclosure | spec expõe regras de crédito/transições | low | accept | Risco aceito (regras já públicas no README; README.md:755-757) | closed |
| T-07-30 | Repudiation | `ShipStock` inválido descartado com pedido SHIPPED (baixa perdida sem rastro) | high | mitigate | `InvalidShipStockException` relançada → DLQ após 3 recebimentos; log ERROR; IT com DLQ real | closed |
| T-07-31 | Denial of Service | `ShipStock` venenoso reentregue para sempre | medium | mitigate | `maxReceiveCount` 3 leva à DLQ; nenhum laço infinito | closed |
| T-07-32 | Tampering | `orderId` do comando inválido levado ao log | medium | mitigate | `sanitizeForLog` do parser; `?` quando ausente | closed |
| T-07-33 | Repudiation | `@Recover` errado escondendo o diagnóstico | low | mitigate | `recover` por nome; IT com `OutputCaptureExtension` distingue os dois WARNs | closed |
| T-07-34 | Repudiation | matriz afirmando cobertura inexistente | medium | mitigate | `check-coverage-matrix.sh` confere cada teste citado contra `git ls-files`; modo estrito recusa `LACUNA`; teste negativo da Task 1 prova que o script acusa lacuna | closed |
| T-07-35 | Tampering | teste novo mascarando bug de produção | low | mitigate | Bug encontrado vira desvio documentado no SUMMARY, não ajuste silencioso | closed |
| T-07-36 | Elevation of Privilege | gatilho de PR com contexto do repositório-alvo expondo o secret a código de fork | high | mitigate | Só `pull_request` (fork não recebe secret) e `push`; actionlint; critério de aceite sobre os gatilhos | closed |
| T-07-37 | Information Disclosure | token impresso em log do CI | high | mitigate | Secret só via `env:` do job; passo de checagem testa `-z` sem ecoar; sem `set -x`; `LocalStackTestSupport` nunca imprime o valor | closed |
| T-07-38 | Tampering | ação comprometida de terceiros | medium | mitigate | Só `actions/*` em major atual; `permissions: contents: read` | closed |
| T-07-39 | Repudiation | testes pulados em silêncio deixando o CI verde | medium | mitigate | `check-no-skipped-tests.sh` no job `guardas` com prova negativa; sem `continue-on-error`; falha explícita sem token | closed |
| T-07-40 | Information Disclosure | smoke imprimindo token/senha | high | mitigate | Mesmo padrão dos smokes anteriores: tokens só em variáveis, nunca em `echo`; mensagens de falha citam status e campos, não credenciais | closed |
| T-07-SC | Tampering | imagem `rhysd/actionlint` usada só localmente para lint | low | accept | Risco aceito (tag fixada 1.7.7; fora do ci.yml e do build) | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

Evidência por ameaça (file:line) verificada pelo gsd-security-auditor em 2026-11-08: os 5 pares `CorrelationContext`/`CorrelationIdFilter` idênticos ao do order-service (regex `[A-Za-z0-9-]{1,64}` + UUID antes do MDC); `MDC.put` só no filtro do Gateway e em `CorrelationContext.open`; nenhum `SecurityConfig.java` alterado na fase (um único `permitAll`, só para docs, guardado por `OpenApiDocsIT` com 401); `ci.yml` só com `push`/`pull_request`, secret só via `env:` e nunca impresso, só ações `actions/*`, `permissions: contents: read`; guardas `check-no-skipped-tests.sh` e `check-adrs.sh` executadas com sucesso; arquivos de evidência da UAT sem tokens.

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-07-01 | T-07-04 | Demo local: Swagger UI e specs públicos no Gateway são intencionais; endpoints de negócio continuam exigindo JWT em cada serviço (D-91). Documentado em README.md:755-757. | Lucas Lopes (plan 07-01) | 2026-11-08 |
| AR-07-02 | T-07-05 | Header gigante: vale o limite padrão de tamanho de header do Tomcat; a regex limita a 64 caracteres o que chega ao MDC e aos logs. | Lucas Lopes (plan 07-01) | 2026-11-08 |
| AR-07-03 | T-07-09 | Atributo SQS forjado: o Correlation-ID só amarra linhas de log, nenhuma decisão de negócio depende dele (D-94); controle de acesso à fila é da infraestrutura. README.md:743-746; ADR 0008. | Lucas Lopes (plan 07-02) | 2026-11-08 |
| AR-07-04 | T-07-13 | Spec expõe contratos, não dados; ferramenta de avaliação local (quick task 260920-g6c). README.md:259-261. | Lucas Lopes (plan 07-03) | 2026-11-08 |
| AR-07-05 | T-07-29 | Regras de crédito e transição já estão públicas no README e na visão geral; a spec não contém dados de clientes. | Lucas Lopes (plan 07-08) | 2026-11-08 |
| AR-07-06 | T-07-SC | Imagem local `rhysd/actionlint` com tag fixada 1.7.7; ferramenta de lint de desenvolvimento, fora do build e do CI. | Lucas Lopes (plan 07-11) | 2026-11-08 |

*Accepted risks do not resurface in future audit runs.*

Resíduos não bloqueantes já registrados em 07-REVIEW.md (não são lacunas de mitigação declarada): IN-02 (secret no `env:` de job, ações fixadas por major tag e não por SHA), IN-04 (guarda de skip não detecta `assumingThat`), WR-02/WR-03 (concurrency por ref × token global; sem `timeout-minutes`), IN-03 (senha demo do admin como exemplo no Swagger, já pública no README).

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-11-08 | 41 | 41 | 0 | gsd-security-auditor (ASVS L1, block_on high) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-11-08
