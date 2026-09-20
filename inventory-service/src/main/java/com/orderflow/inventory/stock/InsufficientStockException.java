package com.orderflow.inventory.stock;

/**
 * Lancada quando a quantidade pedida numa reserva excede o disponivel ({@code onHand - reserved})
 * — 409, semanticamente distinto do 503 de disputa esgotada (D-10 contra D-21). Carrega os dois
 * numeros para o handler compor o corpo de erro; nao ecoa o {@code productId} nem o
 * {@code reservationId} recebidos.
 */
public class InsufficientStockException extends RuntimeException {

    private final int available;
    private final int requested;

    public InsufficientStockException(int available, int requested) {
        super("Insufficient stock available");
        this.available = available;
        this.requested = requested;
    }

    public int getAvailable() {
        return available;
    }

    public int getRequested() {
        return requested;
    }
}
