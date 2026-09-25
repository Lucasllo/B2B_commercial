package com.orderflow.order.order;

import java.util.Set;

/**
 * Os 8 estados da ORD-10, na mesma ordem declarada pelo {@code CHECK} de
 * {@code V1__init_order_schema.sql}. Só {@code CREATED}, {@code PENDING_APPROVAL}, {@code
 * APPROVED} e {@code REJECTED} são alcançados por código nesta fase (D-45); os demais existem no
 * enum e no CHECK agora para que as Fases 5/6 não precisem de uma migração de ALTER só para
 * acrescentá-los depois (04-RESEARCH.md Pitfall 4).
 */
public enum OrderStatus {
    CREATED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    CONFIRMED,
    CANCELLED,
    SHIPPED,
    DELIVERED;

    /**
     * Consomem crédito (D-37): {@code APPROVED}, {@code CONFIRMED}, {@code SHIPPED} e {@code
     * DELIVERED}. {@code PENDING_APPROVAL} não consome enquanto espera; {@code REJECTED} e {@code
     * CANCELLED} liberam. Conjunto único — a lista de status que consome crédito existe num único
     * lugar, nunca duplicada onde a soma de exposição é calculada.
     */
    public static final Set<OrderStatus> CREDIT_CONSUMING = Set.of(APPROVED, CONFIRMED, SHIPPED, DELIVERED);
}
