package com.orderflow.inventory.stock.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Payload de {@code PUT /inventory/{productId}}. Tipar {@code quantityOnHand} como
 * {@link Integer} (nao {@code int} primitivo) e exigir {@code @NotNull} faz um corpo vazio virar
 * 400 em vez de zero silencioso.
 */
@Schema(description = "Define a quantidade física em estoque de um produto.")
public record SetStockRequest(
        @Schema(description = "Nova quantidade física em estoque, zero ou positiva; não pode ficar abaixo da quantidade já reservada",
                example = "100")
        @NotNull @PositiveOrZero Integer quantityOnHand
) {
}
