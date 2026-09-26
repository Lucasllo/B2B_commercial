package com.orderflow.inventory.saga.messaging.dto;

import java.util.UUID;

/**
 * Um item do comando {@link ReserveStockCommand} ou do resultado ({@link StockReservedEvent}) —
 * {@code SAGA_MESSAGE_CONTRACT}. Duplicado por contrato JSON do order-service (D-62), sem módulo
 * compartilhado.
 */
public record ReservationLine(UUID productId, int quantity) {
}
