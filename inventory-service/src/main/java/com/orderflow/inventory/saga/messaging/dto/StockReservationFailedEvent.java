package com.orderflow.inventory.saga.messaging.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de falha publicado na {@code order-events-queue} — {@code SAGA_MESSAGE_CONTRACT}.
 * {@code reasonCode} traz o motivo de negócio (D-56) e {@code failures} o detalhe por produto que
 * causou a falha; {@code RESERVATION_CANCELLED} (lápide, D-66) só passa a ser emitido em 05-04.
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

    public static StockReservationFailedEvent of(UUID eventId, Instant occurredAt, UUID orderId,
                                                   String reservationId, String reasonCode,
                                                   List<ReservationFailureLine> failures) {
        return new StockReservationFailedEvent(eventId, EVENT_TYPE, occurredAt, orderId, reservationId,
                reasonCode, failures);
    }
}
