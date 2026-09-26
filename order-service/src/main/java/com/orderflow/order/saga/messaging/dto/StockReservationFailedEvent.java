package com.orderflow.order.saga.messaging.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de FALHA da reserva consumido da {@code order-events-queue} — {@code
 * SAGA_MESSAGE_CONTRACT} (05-01-PLAN.md {@code interfaces}, publicado pelo inventory-service em
 * {@code 05-02}). Envelope plano: {@code eventId}, {@code eventType}, {@code occurredAt}, depois
 * {@code orderId}/{@code reservationId}/{@code reasonCode}/{@code failures}. {@code reasonCode}
 * traz o motivo de negócio (D-56, vira {@link com.orderflow.order.order.CancellationCode}) e
 * {@code failures} o detalhe por produto.
 */
public record StockReservationFailedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String reservationId,
        String reasonCode,
        List<ReservationFailureLine> failures) {

    public static final String EVENT_TYPE = "StockReservationFailed";

    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String PRODUCT_NOT_STOCKED = "PRODUCT_NOT_STOCKED";
    public static final String RESERVATION_CANCELLED = "RESERVATION_CANCELLED";
}
