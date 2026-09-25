package com.orderflow.order.order.exception;

/**
 * Lançada quando o mesmo {@code productId} aparece mais de uma vez no mesmo pedido — verificado
 * antes de qualquer chamada ao catalog-service (D-44: tudo ou nada, e a checagem mais barata vem
 * primeiro). Mapeada em {@code GlobalExceptionHandler} para 400 {@code validation_failed} com o
 * mesmo envelope da validação de bean ({@code fields.items}), nunca um código de erro novo.
 */
public class DuplicateOrderItemsException extends RuntimeException {

    public DuplicateOrderItemsException() {
        super("each productId may appear only once");
    }
}
