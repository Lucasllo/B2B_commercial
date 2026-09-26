package com.orderflow.order.saga.messaging.dto;

import com.orderflow.order.order.Order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comando de COMPENSAÇÃO enviado à {@code inventory-commands-queue} (D-63) — mesmo envelope de
 * {@link ReserveStockCommand}, mesmo {@code reservationId = orderId.toString()}
 * (RESERVATION_ID_SCOPE=scope-per-product). {@code reason} distingue os dois motivos que geram
 * este comando ao longo da Fase 5: {@link #LATE_RESERVATION} (05-03, Task 2 — um {@code
 * StockReserved} chega para um pedido já {@code CANCELLED}) e {@link #RESERVATION_TIMEOUT}
 * (05-04). É idempotente do lado do inventory-service (D-65, lápide e livro).
 */
public record ReleaseStockCommand(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String reservationId,
        String reason,
        List<ReservationLine> items) {

    public static final String EVENT_TYPE = "ReleaseStock";

    public static final String LATE_RESERVATION = "LATE_RESERVATION";
    public static final String RESERVATION_TIMEOUT = "RESERVATION_TIMEOUT";

    /**
     * Usa {@link Order#getItems()} na ordem de {@code lineNumber} (mesma técnica de {@link
     * ReserveStockCommand#from}) e {@code reservationId = order.getId().toString()}.
     */
    public static ReleaseStockCommand from(Order order, UUID eventId, Instant occurredAt, String reason) {
        List<ReservationLine> lines = order.getItems().stream()
                .map(item -> new ReservationLine(item.getProductId(), item.getQuantity()))
                .toList();
        return new ReleaseStockCommand(
                eventId, EVENT_TYPE, occurredAt, order.getId(), order.getId().toString(), reason, lines);
    }
}
