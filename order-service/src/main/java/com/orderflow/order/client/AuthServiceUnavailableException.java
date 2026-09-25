package com.orderflow.order.client;

/**
 * Lançada quando o auth-service não devolve um limite de crédito confiável (indisponível, timeout,
 * status inesperado, corpo sem {@code creditLimit}, ou {@code companyId} divergente do pedido).
 * Sem limite confiável não há decisão — fail-closed (D-39): nenhum pedido é criado.
 */
public class AuthServiceUnavailableException extends RuntimeException {

    public AuthServiceUnavailableException(String message) {
        super(message);
    }

    public AuthServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
