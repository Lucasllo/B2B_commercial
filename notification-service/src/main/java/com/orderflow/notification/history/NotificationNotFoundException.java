package com.orderflow.notification.history;

/**
 * A linha do tempo pedida nao existe PARA QUEM PEDIU (D-81, D-47): o comprador recebe o mesmo
 * 404 para "pedido de outra empresa", "pedido inexistente" e "pedido sem eventos", de modo que a
 * resposta nunca revela se um pedido de outra empresa existe. A mensagem e fixa de proposito —
 * nenhum identificador entra nela.
 */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException() {
        super("Order not found");
    }
}
