package com.orderflow.inventory.stock.dto;

import com.orderflow.inventory.stock.Inventory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Corpo de resposta de {@code PUT}/{@code GET /inventory/{productId}} e das rotas de reserva e
 * liberacao. {@code quantityAvailable} e sempre {@code quantityOnHand - quantityReserved},
 * exposto como numero — nunca um indicador booleano de disponivel/indisponivel (D-25).
 */
@Schema(description = "Posição de estoque de um produto.")
public record StockResponse(
        @Schema(description = "Produto do catálogo a que o estoque se refere",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID productId,
        @Schema(description = "Quantidade física em estoque", example = "100")
        int quantityOnHand,
        @Schema(description = "Quantidade reservada por pedidos ainda não expedidos", example = "30")
        int quantityReserved,
        @Schema(description = "Quantidade disponível para novas reservas: quantityOnHand menos quantityReserved",
                example = "70")
        int quantityAvailable
) {

    public static StockResponse from(Inventory inventory) {
        return new StockResponse(
                inventory.getProductId(),
                inventory.getQuantityOnHand(),
                inventory.getQuantityReserved(),
                inventory.availableQuantity());
    }
}
