package com.orderflow.inventory.stock.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Payload de {@code PUT /inventory/{productId}}. Tipar {@code quantityOnHand} como
 * {@link Integer} (nao {@code int} primitivo) e exigir {@code @NotNull} faz um corpo vazio virar
 * 400 em vez de zero silencioso.
 */
public record SetStockRequest(
        @NotNull @PositiveOrZero Integer quantityOnHand
) {
}
