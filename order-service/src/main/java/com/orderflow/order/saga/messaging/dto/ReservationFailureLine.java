package com.orderflow.order.saga.messaging.dto;

import java.util.UUID;

/**
 * Detalhe por produto de uma falha de reserva (D-56) — parte de {@link
 * StockReservationFailedEvent#failures()}. {@code available} é a quantidade disponível no momento
 * da avaliação (0 quando o produto não tem linha de estoque, {@code PRODUCT_NOT_STOCKED}). Espelha
 * byte a byte o DTO homônimo do inventory-service (D-62, sem módulo compartilhado) — o contrato é
 * o JSON trocado pela fila, nunca o bytecode desta classe.
 */
public record ReservationFailureLine(UUID productId, int requested, int available) {
}
