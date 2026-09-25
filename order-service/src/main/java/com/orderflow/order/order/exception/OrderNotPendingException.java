package com.orderflow.order.order.exception;

/**
 * Lançada quando uma decisão manual (aprovação ou rejeição) é aplicada a um pedido que não está em
 * {@code PENDING_APPROVAL} — decisão já registrada não pode ser sobrescrita (D-46); nenhum campo
 * de decisão é tocado quando esta exceção é lançada.
 */
public class OrderNotPendingException extends RuntimeException {

    public OrderNotPendingException() {
        super("Order is not pending approval");
    }
}
