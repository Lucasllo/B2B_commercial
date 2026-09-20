package com.orderflow.inventory.stock;

/**
 * Lancada quando {@code setStock} tenta gravar uma quantidade em estoque menor que a ja
 * reservada — 409. Sem esta checagem, a constraint {@code chk_inventory_not_oversold} do banco
 * rejeitaria a escrita com uma mensagem bruta; esta excecao existe para o vendedor receber uma
 * explicacao inteligivel em vez disso.
 */
public class StockBelowReservedException extends RuntimeException {

    private final int reserved;
    private final int requestedOnHand;

    public StockBelowReservedException(int reserved, int requestedOnHand) {
        super("Requested on-hand quantity is below the quantity already reserved");
        this.reserved = reserved;
        this.requestedOnHand = requestedOnHand;
    }

    public int getReserved() {
        return reserved;
    }

    public int getRequestedOnHand() {
        return requestedOnHand;
    }
}
