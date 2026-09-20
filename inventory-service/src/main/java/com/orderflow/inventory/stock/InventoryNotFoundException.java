package com.orderflow.inventory.stock;

/**
 * Lancada quando um {@code productId} de path nao corresponde a nenhuma linha de inventario —
 * ao consultar, definir estoque (nunca — setStock e upsert), reservar ou liberar contra um
 * produto sem linha (D-18).
 */
public class InventoryNotFoundException extends RuntimeException {

    public InventoryNotFoundException(String message) {
        super(message);
    }
}
