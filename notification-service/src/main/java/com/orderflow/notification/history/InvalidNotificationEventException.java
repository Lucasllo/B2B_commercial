package com.orderflow.notification.history;

/**
 * Sinaliza uma mensagem que nunca vai virar registro, por mais que seja reentregue — por isso o
 * listener a descarta em vez de devolve-la a fila (sem DLQ nesta versao, {@code DLQ-01} e v2).
 */
public class InvalidNotificationEventException extends RuntimeException {

    public InvalidNotificationEventException(String reason) {
        super(reason);
    }

    public InvalidNotificationEventException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
