package com.orderflow.order.order.exception;

/**
 * Lançada quando um {@code orderId} de path não corresponde a nenhum pedido persistido, ou quando
 * o pedido existe mas pertence a outra empresa e quem pede não é SELLER_ADMIN (a mesma mensagem
 * dos dois casos — um pedido alheio é indistinguível de inexistente para o comprador, D-47).
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
