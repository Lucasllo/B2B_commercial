package com.orderflow.order.order;

import java.util.Set;

/**
 * Os 9 estados da ORD-10, na mesma ordem declarada pelo {@code CHECK} de
 * {@code V2__order_reservation_saga.sql} (o {@code CHECK} nasceu em {@code V1__init_order_schema.sql}
 * com 8 valores; a Fase 5 o larga e recria com {@code RESERVING} incluído, D-49). {@code CREATED},
 * {@code PENDING_APPROVAL}, {@code APPROVED}, {@code REJECTED} e agora {@code RESERVING} são
 * alcançados por código; {@code CONFIRMED}/{@code CANCELLED}/{@code SHIPPED}/{@code DELIVERED}
 * existem no enum e no CHECK agora para que as Fases 5/6 não precisem de uma migração de ALTER só
 * para acrescentá-los depois (04-RESEARCH.md Pitfall 4).
 *
 * <p>{@code APPROVED} é, a partir da Fase 5, um passo lógico da decisão (D-50) — registrado em
 * {@code decidedBy}/{@code decidedAt}/{@code reason} — nunca um estado em que um pedido novo
 * repousa: a entrada em aprovação (automática ou manual) grava direto {@code RESERVING} na mesma
 * transação que insere o comando de reserva no outbox (D-48). O valor {@code APPROVED} permanece
 * no enum só por compatibilidade semântica e por pedidos históricos anteriores à Fase 5.
 */
public enum OrderStatus {
    CREATED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    RESERVING,
    CONFIRMED,
    CANCELLED,
    SHIPPED,
    DELIVERED;

    /**
     * Consomem crédito (D-37, D-52): {@code APPROVED}, {@code RESERVING}, {@code CONFIRMED},
     * {@code SHIPPED} e {@code DELIVERED}. {@code PENDING_APPROVAL} não consome enquanto espera;
     * {@code REJECTED} e {@code CANCELLED} liberam. Conjunto único — a lista de status que consome
     * crédito existe num único lugar, nunca duplicada onde a soma de exposição é calculada.
     */
    public static final Set<OrderStatus> CREDIT_CONSUMING =
            Set.of(APPROVED, RESERVING, CONFIRMED, SHIPPED, DELIVERED);
}
