# Phase 5: Saga de Reserva de Estoque — Outbox, Compensação e Confirmação - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-25
**Phase:** 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
**Areas discussed:** Gatilho e estado intermediário, Reserva multi-item e falha, Mecânica do Outbox, Garantia de "nunca travado"

---

## Gatilho e estado intermediário

| Pergunta | Opções | Escolha |
|----------|--------|---------|
| Quando disparar a reserva? | Toda entrada em APPROVED / Só aprovação automática / Endpoint explícito de confirmar | ✓ Toda entrada em APPROVED |
| Estado de "reserva em andamento" | Status novo RESERVING / APPROVED como intermediário / Flag reservationStatus | ✓ RESERVING |
| APPROVED persistido? | Mesma transação grava direto RESERVING / APPROVED persistido e depois RESERVING | ✓ Mesma transação |
| Pedidos APPROVED legados | Migração gera comando / Ignorar (dados de demo) / Você decide | ✓ Migração gera comando |
| Onde registrar o motivo do CANCELLED | Colunas próprias da saga / Sobrescrever `reason` / Tabela de histórico de status | ✓ Colunas próprias |
| RESERVING consome crédito? | Sim (CREDIT_CONSUMING) / Não | ✓ Sim |
| Resposta dos POST de criação/aprovação | RESERVING com mesmos códigos HTTP / 202 Accepted / Bloqueante | ✓ RESERVING, mesmos códigos |
| Demo na stack real | Script smoke / Só o E2E | ✓ Script smoke |

## Reserva multi-item e falha

| Pergunta | Opções | Escolha |
|----------|--------|---------|
| Processamento de N itens | Tudo ou nada numa transação / Item a item com compensação / Um comando por item | ✓ Tudo ou nada |
| Conteúdo da falha | Código + detalhe por produto / Só código genérico | ✓ Código + detalhe |
| Estoque no CONFIRMED | Fica reservado, baixa na Fase 6 / Baixa on_hand já no CONFIRMED | ✓ Fica reservado |
| Produto sem linha de estoque | Falha de negócio → CANCELLED / Tratar como disponível 0 | ✓ Falha de negócio |

## Mecânica do Outbox

| Pergunta | Opções | Escolha |
|----------|--------|---------|
| Relay | Polling @Scheduled / After-commit + polling de reserva / CDC (Debezium) | ✓ Polling @Scheduled |
| Outbox no inventory-service | Resultado + STOCK_ADJUSTED / Só resultado / Nenhum | ✓ Resultado + STOCK_ADJUSTED |
| Topologia de filas | Uma por direção / Uma por tipo de evento / SNS fan-out + SQS | ✓ Uma por direção |
| Código do outbox | Duplicado em cada serviço / Módulo compartilhado | ✓ Duplicado |

## Garantia de "nunca travado"

| Pergunta | Opções | Escolha |
|----------|--------|---------|
| Inventory nunca responde | Timeout cancela + ReleaseStock / Só retry SQS + DLQ / Timeout sem compensação | ✓ Timeout + compensação |
| Idempotência no order-service | Guarda por estado / Tabela processed_messages / Ambos | ✓ Guarda por estado |
| Corrida Release antes de Reserve | Lápide no release / Fila FIFO / Aceitar e documentar | ✓ Lápide |
| Valores de timeout/retry/DLQ | Você decide (configurável) / Definir agora | ✓ Você decide |
| Estrutura do E2E | Módulo e2e-tests em processo / Testcontainers com imagens / IT no order-service com inventory fake | ✓ Módulo e2e-tests |

## Claude's Discretion

- Valores padrão de relay, timeout, maxReceiveCount e lote.
- Envelope dos eventos, nomes de filas, tipos e colunas.
- Limpeza do outbox; trava de crédito no CANCELLED; destino dos endpoints REST de reserva do inventory.

## Deferred Ideas

- Baixa de on_hand na expedição (Fase 6); eventos da saga no notification-service (Fase 6); FIFO/SNS; módulo compartilhado de outbox; cancelamento pelo comprador.
