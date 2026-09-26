package com.orderflow.inventory.saga.messaging.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de sucesso publicado na {@code order-events-queue} — {@code SAGA_MESSAGE_CONTRACT}.
 * Envelope plano, mesma convenção de {@link ReserveStockCommand}: {@code eventId} (igual ao id da
 * linha do outbox deste serviço que o origina — um novo {@code eventId} a cada emissão, inclusive
 * em replay, D-65), {@code eventType}, {@code occurredAt}, depois {@code orderId}/
 * {@code reservationId}/{@code items} (os itens efetivamente reservados).
 */
public record StockReservedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String reservationId,
        List<ReservationLine> items) {

    public static final String EVENT_TYPE = "StockReserved";

    public static StockReservedEvent of(UUID eventId, Instant occurredAt, UUID orderId, String reservationId,
                                         List<ReservationLine> items) {
        return new StockReservedEvent(eventId, EVENT_TYPE, occurredAt, orderId, reservationId, items);
    }
}
