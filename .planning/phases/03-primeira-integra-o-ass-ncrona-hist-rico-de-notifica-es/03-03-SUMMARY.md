---
phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
plan: "03"
subsystem: infra
tags: [docker-compose, spring-cloud-gateway, localstack, sqs, dynamodb, springdoc-openapi, bash]

# Dependency graph
requires:
  - phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
    provides: "notification-service consumidor (03-01) e inventory-service produtor (03-02), cada um exercitado só na própria suíte"
provides:
  - "notification-service entra no docker compose up, atrás do API Gateway (rota /api/notifications/**), com healthcheck próprio na porta 8084"
  - "Init hook do LocalStack provisiona fila notification-events-queue e tabela notification-history automaticamente na subida, sem passo manual — healthcheck do LocalStack só passa depois de confirmar os dois recursos"
  - "scripts/smoke-notification-flow.sh: prova de ponta a ponta na stack real — fluxo PUT->fila->DynamoDB->GET pelo Gateway, reentrega sem duplicação (mesmo eventId sobrescreve) e ajustes distintos acumulando, contados por ocorrências de \"recordedAt\""
  - "Swagger UI do notification-service (porta 8084) com esquema bearerAuth declarado, provado por OpenApiDocsIT"
  - "README.md, docs/API.md e docs/VISAO-GERAL.md documentam o endpoint de histórico, o caminho do evento SQS->DynamoDB e as limitações conhecidas da Fase 3 (dual-write D-29/D-30, sem DLQ, sem política de acesso da fila, sem ordem garantida)"
affects: [05-saga-reserva-estoque, 06-linha-do-tempo-do-pedido]

# Actuals (#2632)
actuals:
  tokens: 10400
  tasks: 2
  commits: 2

plan_head_before: 65a79c984b6802a170e9f8be218b8e96038ba315

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Compose healthcheck que verifica recursos de negócio (awslocal sqs get-queue-url && dynamodb describe-table), não só o processo — o endpoint de saúde do LocalStack responde antes do init hook terminar, então um healthcheck de processo deixaria serviços subirem contra uma fila inexistente"
    - "Script de smoke roda toda chamada HTTP de dentro do container do Gateway (docker compose exec -T gateway curl ...), nunca do host — funciona igual em qualquer SO do host, inclusive Git Bash do Windows com MSYS_NO_PATHCONV=1"
    - "Reentrega simulada como duas entregas do mesmo corpo (mesmo eventId) direto na fila via awslocal sqs send-message — do ponto de vista do consumidor, indistinguível de uma reentrega real do SQS"
    - "Contagem de elementos do histórico por ocorrências de \"recordedAt\" no corpo JSON — eventId/eventType aparecem duas vezes por elemento (nível do elemento e dentro do payload aninhado), recordedAt só uma vez"

key-files:
  created:
    - notification-service/src/main/java/com/orderflow/notification/config/OpenApiConfig.java
    - notification-service/src/test/java/com/orderflow/notification/OpenApiDocsIT.java
  modified:
    - docker-compose.yml
    - gateway/src/main/resources/application.yml
    - scripts/smoke-notification-flow.sh
    - README.md
    - docs/API.md
    - docs/VISAO-GERAL.md

key-decisions:
  - "Healthcheck do LocalStack verifica fila e tabela via awslocal, não só o processo — decisão do plano, confirmada necessária pelo Pitfall D de 03-RESEARCH.md"
  - "Reentrega provada enviando duas vezes o mesmo corpo (mesmo eventId) direto na fila via awslocal, em vez de derrubar o consumidor no meio do processamento — mesma suposição carregada de 03-CONTEXT.md (NOTF-01, unclassified/unresolved)"
  - "Prova automatizada de autorização pelo Gateway é o 401 sem token; o 403 do BUYER pelo Gateway fica só como verificação manual documentada (já coberto por teste automatizado dentro do próprio serviço, NotificationControllerIT) — mesma suposição carregada de 03-CONTEXT.md (NOTF-02, unclassified/unresolved)"

patterns-established:
  - "Pattern: healthcheck de infraestrutura local (LocalStack) que verifica recursos de negócio provisionados, não apenas o processo respondendo — referência para qualquer fase futura que dependa de um recurso criado por init hook"
  - "Pattern: script de smoke bash que roda toda chamada HTTP de dentro de um container do próprio compose, nunca do host — portável entre SOs, referência para scripts de smoke de fases futuras"

requirements-completed: [NOTF-01, NOTF-02]

coverage:
  - id: D1
    description: "docker compose up -d --build --wait sobe sete serviços saudáveis a partir de um .env preenchido; fila e tabela nascem sozinhas pelo init hook do LocalStack, sem nenhum passo manual"
    requirement: "NOTF-01"
    verification:
      - kind: automated
        ref: "docker compose config --quiet; docker compose up -d --build --wait; docker compose ps --format '{{.Service}} {{.State}} {{.Health}}' (7/7 healthy)"
        status: pass
      - kind: automated
        ref: "docker compose exec -T localstack awslocal --region us-east-1 sqs get-queue-url --queue-name notification-events-queue"
        status: pass
    human_judgment: false
  - id: D2
    description: "Um PUT /api/inventory/{productId} pelo Gateway aparece, em segundos, como evento STOCK_ADJUSTED em GET /api/notifications/{productId} pelo Gateway, sem nenhuma chamada REST entre inventory-service e notification-service"
    requirement: "NOTF-01"
    verification:
      - kind: e2e
        ref: "scripts/smoke-notification-flow.sh (passos 1-5, SMOKE OK, evento visível em 0s na última execução)"
        status: pass
      - kind: manual_procedural
        ref: "Task 1 human-check aprovado pelo usuário: DynamoDB scan de notification-history mostrou item com productId igual ao UUID do smoke e sortKey iniciando com STOCK_ADJUSTED#; docker compose logs notification-service mostrou apenas startup + SQS listener, nenhuma chamada HTTP de entrada do inventory-service"
        status: pass
    human_judgment: false
  - id: D3
    description: "Reentrega (mesmo eventId enviado duas vezes direto na fila) sobrescreve em vez de duplicar (1 elemento); dois ajustes distintos do mesmo produto pelo Gateway acumulam (3 elementos), ambos provados na stack real"
    requirement: "NOTF-01"
    verification:
      - kind: e2e
        ref: "scripts/smoke-notification-flow.sh (passos 6-7, contagem por ocorrências de \"recordedAt\")"
        status: pass
    human_judgment: false
  - id: D4
    description: "GET /api/notifications/{productId} pelo Gateway sem token devolve 401 (rota existe, prefixo removido, validação local); rota de inventory-service não regrediu"
    requirement: "NOTF-02"
    verification:
      - kind: e2e
        ref: "scripts/smoke-notification-flow.sh (passo 3); docker compose exec -T gateway curl .../api/inventory/{uuid} -> 401"
        status: pass
    human_judgment: false
  - id: D5
    description: "Swagger UI e spec OpenAPI do notification-service (porta 8084) acessíveis sem token, esquema bearerAuth declarado corretamente, endpoint de negócio continua exigindo token; BUYER pelo Gateway recebe 403 na consulta"
    requirement: "NOTF-02"
    verification:
      - kind: integration
        ref: "notification-service/src/test/java/com/orderflow/notification/OpenApiDocsIT.java (4/4 pass)"
        status: pass
    human_judgment: true
    rationale: "O 403 do BUYER pelo Gateway via Try it out da Swagger UI e a navegação visual do botão Authorize exigem confirmação humana — deferida ao UAT de fim de fase (workflow.human_verify_mode=end-of-phase); o 403 em si já está coberto por teste automatizado dentro do serviço (NotificationControllerIT, planos anteriores)."
  - id: D6
    description: "README.md, docs/API.md e docs/VISAO-GERAL.md documentam o endpoint de histórico com papel exigido, o caminho do evento SQS->DynamoDB, o script de smoke, e uma seção de limitações conhecidas que declara o risco de dual-write (D-30) e a ausência de DLQ, de forma legível para um avaliador que não abre o código"
    requirement: "NOTF-02"
    verification:
      - kind: automated
        ref: "grep -c '/api/notifications/' README.md (3); grep -c 'Limitações conhecidas' README.md (1); grep -c '/api/notifications/{productId}' docs/API.md (3)"
        status: pass
    human_judgment: true
    rationale: "Clareza do texto de limitações conhecidas para um avaliador externo que não lê código é julgamento humano — deferida ao UAT de fim de fase (workflow.human_verify_mode=end-of-phase)."

duration: ~37min (Task 1: tracer + checkpoint humano, sessão anterior; Task 2: ~22min, esta sessão)
completed: 2026-09-23
status: complete
---

# Phase 3 Plan 3: Compose, Gateway, smoke e documentação — fechamento da fatia vertical Summary

**O `notification-service` entra no `docker compose up` atrás do API Gateway, com fila e tabela do LocalStack provisionadas automaticamente pelo init hook; um script de smoke prova na stack real que um `PUT /api/inventory/{id}` pelo Gateway vira histórico consultável em segundos, que reentrega não duplica e que ajustes distintos acumulam; e README/docs/API.md/VISAO-GERAL.md documentam o endpoint, o caminho do evento e as limitações conhecidas (dual-write sem outbox, sem DLQ) para um avaliador externo.**

## Performance

- **Duration:** ~37 min no total (Task 1 — tracer, subida da stack completa e checkpoint humano — concluída em sessão anterior; Task 2 — OpenApiConfig/OpenApiDocsIT, extensão do smoke, README/docs — ~22 min nesta sessão de continuação)
- **Tasks:** 2 (`type="tracer"` e `type="auto" tdd="true"`)
- **Files modified:** 8 (2 criados, 6 modificados)

## Accomplishments

- `docker-compose.yml`: bloco `notification-service` (build, `depends_on` `localstack`/`auth-service` saudáveis, `SPRING_CLOUD_AWS_ENDPOINT`, JWKS por nome de serviço, porta `127.0.0.1:8084:8084`); `inventory-service` passa a depender de `localstack` saudável; `localstack` ganha o volume do init hook e um healthcheck que só passa depois que a fila e a tabela existem de fato; `gateway` passa a depender de `notification-service` saudável. Nenhuma imagem nova com tag flutuante.
- `gateway/src/main/resources/application.yml`: rota `notification-service-route` (`Path=/api/notifications/**`, `StripPrefix=1`), mesma estrutura das rotas existentes, sem nenhuma configuração de segurança no Gateway (D-05).
- `scripts/smoke-notification-flow.sh`: prova de ponta a ponta contra a stack real — login, geração de UUID dentro do container do Gateway, 401 sem token, `PUT` do ajuste, espera até 15s pelo evento no histórico (Task 1); reentrega do mesmo `eventId` direto na fila deixando 1 elemento após a fila esvaziar, e dois ajustes distintos acumulando 3 elementos (Task 2) — ambos contados por ocorrências de `"recordedAt"`. Nunca imprime o token. `git update-index --chmod=+x` aplicado.
- `OpenApiConfig`/`OpenApiDocsIT` do `notification-service`: RED (título padrão do springdoc, sem esquema `bearerAuth`) → GREEN (4/4 testes, esquema declarado, endpoint de negócio continua 401 sem token).
- `README.md`: parágrafo "O que já funciona hoje" ampliado para Fases 1-3; Execução/Build e testes/Swagger UI citam o `notification-service` e o `LOCALSTACK_AUTH_TOKEN` exigido pelos testes de integração; novas seções "Histórico de notificações", "Como o evento flui (SQS → DynamoDB)" (com comandos de inspeção de fila/tabela) e "Limitações conhecidas (Fase 3)" (dual-write D-29/D-30, sem DLQ, sem política de acesso da fila, sem ordem garantida de entrega); fluxo de demonstração ganha um passo final consultando o histórico.
- `docs/API.md`: linha na tabela de visão geral, seção completa `GET /api/notifications/{productId}` (autenticação, exemplo de resposta com evento e lista vazia, erros 400/401/403/503).
- `docs/VISAO-GERAL.md`: `notification-service`, fila e tabela no diagrama de arquitetura e na descrição do estado atual; Swagger UI na porta 8084.

## Task Commits

Each task was committed atomically:

1. **Task 1: Tracer — um ajuste de estoque pelo Gateway aparece no histórico consultado pelo Gateway, com tudo subindo num comando e sem passo manual** — `cef3262` (feat) — sessão anterior; checkpoint `human-verify` (`gate="blocking-human"`) aprovado pelo usuário com evidência de DynamoDB scan e logs sem chamada HTTP de entrada.
2. **Task 2: O avaliador reproduz a garantia de reentrega na stack real e encontra tudo documentado** — `2f2e76d` (feat) — RED (`OpenApiDocsIT` falhando no título/`bearerAuth`, springdoc default) → GREEN (4/4 testes) + extensão do smoke + documentação, nesta sessão.

**Plan metadata:** commit de documentação final a ser criado logo após este SUMMARY.

## Files Created/Modified

- `docker-compose.yml` — bloco `notification-service`, dependência do `inventory-service` e do `gateway` no LocalStack/notification-service saudáveis, healthcheck de provisionamento no `localstack` (Task 1)
- `gateway/src/main/resources/application.yml` — rota `notification-service-route` (Task 1)
- `scripts/smoke-notification-flow.sh` — fluxo pelo Gateway (Task 1); reentrega e ajustes distintos (Task 2)
- `notification-service/src/main/java/com/orderflow/notification/config/OpenApiConfig.java` — esquema `bearerAuth` + `SecurityRequirement` global (Task 2)
- `notification-service/src/test/java/com/orderflow/notification/OpenApiDocsIT.java` — 4 testes espelhando o do `inventory-service` (Task 2)
- `README.md` — histórico de notificações, caminho do evento, limitações conhecidas, fluxo de demonstração estendido (Task 2)
- `docs/API.md` — referência do endpoint `GET /api/notifications/{productId}` (Task 2)
- `docs/VISAO-GERAL.md` — `notification-service`/SQS/DynamoDB no estado atual e na arquitetura (Task 2)

## Decisions Made

- Ver `key-decisions` no frontmatter — nenhuma decisão de Claude's Discretion nova além das já travadas em `03-CONTEXT.md`/`03-01-PLAN.md`/`03-02-PLAN.md`; este plano só materializou as suposições carregadas (reentrega simulada via envio direto na fila; prova automatizada de autorização limitada ao 401).

## Deviations from Plan

None - plan executado exatamente como escrito, em ambas as tasks.

## Issues Encountered

- Build das imagens `catalog-service`/`inventory-service` precisou de novas tentativas por falhas transitórias de rede ao buscar dependências opcionais do Flyway (ambiental, documentado no checkpoint da Task 1; não relacionado ao código deste plano).

## User Setup Required

None - nenhuma configuração nova além da já registrada na Fase 1 (`LOCALSTACK_AUTH_TOKEN`, `.env`).

## Next Phase Readiness

- A Fase 3 está fechada como fatia vertical de verdade: sete serviços sobem com um único `docker compose up`, fila e tabela nascem sozinhas, e o fluxo `PUT /api/inventory/{id}` → fila → DynamoDB → `GET /api/notifications/{id}` está provado na stack real, com reentrega sem duplicação e ajustes distintos acumulando.
- A Fase 5 (saga de reserva de estoque) reaproveita o padrão de healthcheck de recursos de infraestrutura provisionados por init hook e o padrão de script de smoke executando dentro do container do Gateway — mas precisa substituir a publicação direta no SQS por Transactional Outbox onde a saga exigir atomicidade real entre DB e evento (D-29/D-30, declarado nas limitações conhecidas do README).
- Itens deferidos para o UAT de fim de fase (`workflow.human_verify_mode=end-of-phase`): D5 (BUYER 403 pelo Gateway via Swagger UI, navegação do botão Authorize) e D6 (clareza do texto de limitações conhecidas para um avaliador externo) — ver seção `coverage` acima.
- Nenhum bloqueio conhecido. Stack ainda em execução (7/7 saudáveis) ao final deste plano, deixada de propósito para o UAT de fim de fase reaproveitar sem precisar subir de novo.

## Threat Flags

Nenhuma superfície nova além das já registradas no `<threat_model>` do plano (T-03-20 a T-03-26, T-03-SC) — todas com disposição `mitigate`/`accept` implementadas:
- T-03-20 (rota do Gateway sem autenticação) — mitigada: 401 confirmado automaticamente pelo smoke e pelo gate `docker compose exec -T gateway curl`.
- T-03-21 (porta 8084 exposta além do host) — mitigada: publicação restrita a `127.0.0.1:8084:8084`.
- T-03-22 (fila/tabela legíveis por qualquer processo local via 4566) — aceita, já documentada nas limitações conhecidas do README.
- T-03-23 (tag flutuante) — mitigada: nenhuma imagem nova, build local com Dockerfile.
- T-03-24 (serviços subindo antes de fila/tabela existirem) — mitigada: healthcheck do LocalStack e `depends_on: service_healthy`.
- T-03-25 (token impresso pelo script) — mitigada: `TOKEN` nunca aparece em nenhum `echo`/`fail`.
- T-03-26 (credencial de demonstração reutilizada) — aceita, já documentada na Fase 1.
- T-03-SC (cadeia de suprimentos) — aceita: nenhuma dependência nova; script usa só `bash`/`curl`/`sed`/`grep`/`awslocal` já presentes.

## Known Stubs

Nenhum. O `notification-service` está no compose, atrás do Gateway, com fila/tabela reais e fluxo provado de ponta a ponta na stack real — nenhum dado mock ou placeholder.

---
*Phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es*
*Completed: 2026-09-23*

## Self-Check: PASSED

Verificação dos arquivos e commits citados:
- `docker-compose.yml`, `gateway/src/main/resources/application.yml`,
  `scripts/smoke-notification-flow.sh`,
  `notification-service/src/main/java/com/orderflow/notification/config/OpenApiConfig.java`,
  `notification-service/src/test/java/com/orderflow/notification/OpenApiDocsIT.java`,
  `README.md`, `docs/API.md`, `docs/VISAO-GERAL.md` — todos encontrados.
- Commits `cef3262`, `2f2e76d` — ambos encontrados em `git log --oneline --all`.
- `./mvnw -B -pl auth-service,catalog-service,inventory-service,notification-service verify` —
  BUILD SUCCESS (18 testes em `notification-service`, incluindo `OpenApiDocsIT` 4/4).
- `bash scripts/smoke-notification-flow.sh` — `SMOKE OK`, 7/7 passos confirmados na última execução.
