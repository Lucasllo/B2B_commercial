package com.orderflow.inventory.stock.dto;

import com.orderflow.inventory.stock.Inventory;

import java.util.UUID;

/**
 * Corpo de resposta de {@code PUT}/{@code GET /inventory/{productId}} e das rotas de reserva e
 * liberacao. {@code quantityAvailable} e sempre {@code quantityOnHand - quantityReserved},
 * exposto como numero — nunca um indicador booleano de disponivel/indisponivel (D-25).
 */
public record StockResponse(
        UUID productId,
        int quantityOnHand,
        int quantityReserved,
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
