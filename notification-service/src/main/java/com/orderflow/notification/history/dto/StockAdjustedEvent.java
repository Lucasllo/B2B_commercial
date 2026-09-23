package com.orderflow.notification.history.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Duplica por contrato JSON o {@code record} equivalente do {@code inventory-service} — sem
 * modulo Maven compartilhado (03-RESEARCH.md §Alternatives Considered). O {@code eventId} e
 * gerado uma unica vez na publicacao — nunca o identificador de mensagem do SQS, que muda a cada
 * entrega.
 *
 * <p>As quantidades sao {@link Integer}, nao {@code int}, de proposito: o consumidor nao confia
 * no produtor, e com tipo primitivo um campo ausente no JSON viraria zero em silencio em vez de
 * ser detectado como evento invalido (Task 2).
 */
public record StockAdjustedEvent(
        UUID eventId,
        String eventType,
        UUID productId,
        Integer previousQuantityOnHand,
        Integer newQuantityOnHand,
        Instant occurredAt
) {
    public static final String EVENT_TYPE = "STOCK_ADJUSTED";
}
