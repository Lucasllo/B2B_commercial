package com.orderflow.order.saga.messaging.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de SUCESSO da reserva consumido da {@code order-events-queue} — {@code
 * SAGA_MESSAGE_CONTRACT} (05-01-PLAN.md {@code interfaces}, publicado pelo inventory-service em
 * {@code 05-02}). Envelope plano: {@code eventId}, {@code eventType}, {@code occurredAt}, depois
 * {@code orderId}/{@code reservationId}/{@code items} — os itens EFETIVAMENTE reservados, usados
 * por {@code OrderSagaService} para conferir contra os itens do pedido antes de confirmar
 * ({@code STOCK_RESERVED_ITEMS_CHECK}).
 */
public record StockReservedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String reservationId,
        List<ReservationLine> items) {

    public static final String EVENT_TYPE = "StockReserved";
}
