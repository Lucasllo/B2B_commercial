package com.orderflow.inventory.saga.messaging.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comando de EXPEDICAO recebido da {@code inventory-commands-queue} (D-75) — publicado pelo
 * order-service em {@code POST /orders/{id}/ship} (06-02). Mesmo envelope de {@link
 * ReserveStockCommand} (mesmo {@code reservationId = orderId.toString()}), sem {@code reason}.
 * {@code items} serve so para dizer QUAIS produtos do pedido baixar: a quantidade efetivamente
 * baixada vem sempre do livro {@code stock_reservations} ({@code InventoryService#shipAll}), nunca
 * deste corpo. Nenhuma resposta e devolvida ao order-service (o pedido nao espera, D-75).
 *
 * <p>Duplicado por contrato JSON do order-service (D-62, sem modulo compartilhado) — validado por
 * {@link com.orderflow.inventory.saga.messaging.SagaCommandParser} antes de qualquer uso.
 */
public record ShipStockCommand(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String reservationId,
        List<ReservationLine> items) {

    public static final String EVENT_TYPE = "ShipStock";
}
