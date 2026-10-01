package com.orderflow.order.order.exception;

import com.orderflow.order.order.OrderStatus;

/**
 * Lançada quando uma ação de ciclo de vida do vendedor ({@code ship}, {@code deliver}) é aplicada
 * a um pedido cujo status atual não é a origem da aresta que a ação representa em {@link
 * OrderStatus#transitions()} (D-77, ORD-10). Nenhum campo do pedido é tocado quando ela é lançada.
 *
 * <p>A mensagem carrega só nomes de enum — {@code Order cannot transition from <ATUAL> to
 * <DESTINO>} — nunca entrada do usuário (INVALID_TRANSITION_ERROR, T-06-08); vira 409 {@code
 * invalid_order_transition} em {@code GlobalExceptionHandler}. {@code approve}/{@code reject}
 * continuam com {@link OrderNotPendingException} (contrato da Fase 4 preservado).
 */
public class InvalidOrderTransitionException extends RuntimeException {

    private final OrderStatus from;
    private final OrderStatus to;

    public InvalidOrderTransitionException(OrderStatus from, OrderStatus to) {
        super("Order cannot transition from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public OrderStatus getFrom() {
        return from;
    }

    public OrderStatus getTo() {
        return to;
    }
}
