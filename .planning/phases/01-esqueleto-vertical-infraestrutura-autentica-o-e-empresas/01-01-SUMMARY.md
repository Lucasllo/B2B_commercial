---
phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
plan: "01"
subsystem: decisão-de-stack
tags: [spring-boot, spring-cloud, versionamento, decisão-arquitetural]

dependency-graph:
  requires: []
  provides:
    - "SPRING_BOOT_LINE (linha de versão do Spring Boot herdada por todos os 5 microsserviços)"
    - "SPRING_CLOUD_TRAIN (trem do Spring Cloud compatível, herdado pelo pom.xml pai)"
  affects:
    - "01-02-PLAN.md (pom.xml pai — propriedades <spring-boot.version> e <spring-cloud.version>)"

tech-stack:
  added: []
  patterns:
    - "Decisão de framework de mão única confirmada por checkpoint humano (gate=blocking-human) antes de qualquer pom.xml existir"

key-files:
  created:
    - ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-01-SUMMARY.md"
  modified: []

decisions:
  - "Escolhida a opção boot-3-5: Spring Boot 3.5.16 + Spring Cloud 2025.0.3 (Northfields), mantendo a decisão já justificada em .claude/CLAUDE.md, mesmo com a linha 3.5 fora do suporte OSS ativo — tradeoff consciente de estabilidade/baixo risco de migração em favor do domínio (saga, outbox, limite de crédito)."

metrics:
  duration: "5m"
  completed: "2026-09-18"

status: complete
---

# Phase 01 Plan 01: Decisão de versão Spring Boot / Spring Cloud Summary

Confirmação humana explícita, via checkpoint de decisão, de que os cinco microsserviços do
OrderFlow herdarão a linha Spring Boot 3.5.16 + Spring Cloud 2025.0.3 "Northfields", registrada
de forma legível por máquina para consumo direto pelo plano `01-02`.

## Decisão Registrada

SPRING_BOOT_LINE=3.5.16
SPRING_CLOUD_TRAIN=2025.0.3

**Opção escolhida:** `boot-3-5` — Spring Boot 3.5.16 + Spring Cloud 2025.0.3 "Northfields" (a
escolha já justificada em `.claude/CLAUDE.md`).

**Status de suporte OSS na data da decisão:** a linha Spring Boot 3.5 encerrou o suporte
open-source em 2026-06-30, sendo `3.5.16` o último patch OSS publicado; a partir dessa data,
patches de segurança adicionais para a linha 3.5 só estão disponíveis via oferta comercial
(Spring/VMware). Esta decisão assume esse tradeoff conscientemente: prioriza estabilidade,
compatibilidade validada em `01-RESEARCH.md` e ausência de retrabalho de migração (Jackson 3 /
Jakarta EE 11 / Servlet 6.1) em troca de não estar na linha com suporte OSS ativo. Esse tradeoff
deve ser registrado como ADR explícito na Fase 7 (requisito INFRA-03), e o `README` do projeto
deve deixar claro que é uma escolha deliberada, não uma omissão.

Como a opção escolhida foi `boot-3-5` (não `boot-4-0`), a `01-RESEARCH.md` permanece válida
como está — nenhuma reverificação de compatibilidade é necessária antes do plano `01-02` escrever
o `pom.xml` pai.

## O que foi feito

- Tarefa única do plano: checkpoint de decisão (`type="checkpoint:decision"`, `gate="blocking-human"`)
  apresentado ao desenvolvedor com as opções `boot-3-5` e `boot-4-0`, cada uma com prós/contras e
  o fato de EOL do suporte OSS da linha 3.5 exposto explicitamente antes da escolha.
- O desenvolvedor respondeu `boot-3-5`.
- Nenhum arquivo de código-fonte, `pom.xml` ou `docker-compose.yml` foi criado ou modificado —
  apenas este documento de decisão, conforme as `acceptance_criteria` do plano.

## Deviations from Plan

None - plan executado exatamente como escrito. O checkpoint já havia sido resolvido pelo humano
antes da invocação deste executor; a única ação pendente era registrar a decisão no formato
exigido pelo plano `01-02`.

## Self-Check: PASSED

- FOUND: .planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-01-SUMMARY.md
- Nenhum outro arquivo foi criado ou modificado por este plano (verificado via `git status --short`).
