package com.orderflow.inventory.saga.messaging.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comando de COMPENSAÇÃO recebido da {@code inventory-commands-queue} (D-63, D-66) — mesmo
 * envelope de {@link ReserveStockCommand}, mesmo {@code reservationId = orderId.toString()}.
 * {@code reason} distingue os dois motivos que o order-service usa para gerá-lo (05-04):
 * {@link #RESERVATION_TIMEOUT} (job de timeout, nenhum resultado chegou a tempo) e
 * {@link #LATE_RESERVATION} ({@code StockReserved} chegando depois de o pedido já estar {@code
 * CANCELLED}). Idempotente do lado deste serviço (D-65/D-66 — livro e lápide,
 * {@code InventoryService#releaseAll}); nenhuma resposta é devolvida ao order-service.
 *
 * <p>Duplicado por contrato JSON do order-service (D-62, sem módulo compartilhado) — validado por
 * {@link com.orderflow.inventory.saga.messaging.SagaCommandParser} antes de qualquer uso.
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

    public static final String RESERVATION_TIMEOUT = "RESERVATION_TIMEOUT";
    public static final String LATE_RESERVATION = "LATE_RESERVATION";
}
