package com.orderflow.order.order.exception;

/**
 * Lançada quando a soma dos subtotais de um pedido não cabe em {@code NUMERIC(19,2)} — mais de 17
 * dígitos na parte inteira, mesmo critério do {@code @Digits(integer = 17, fraction = 2)} usado
 * por {@code CreateCompanyRequest} no auth-service. Verificada em {@code OrderCreationService}
 * depois de precificar todos os itens e ANTES de consultar o limite de crédito (04-02 Task 3) —
 * um total que não cabe na coluna nunca deveria estourar como 500 no banco.
 */
public class OrderTotalOutOfRangeException extends RuntimeException {

    public OrderTotalOutOfRangeException() {
        super("Order total does not fit in the allowed range");
    }
}
