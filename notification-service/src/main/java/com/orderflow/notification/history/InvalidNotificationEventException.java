package com.orderflow.notification.history;

/**
 * Sinaliza uma mensagem que nunca vai virar registro, por mais que seja reentregue — por isso o
 * listener a descarta em vez de devolve-la a fila (sem DLQ nesta versao, {@code DLQ-01} e v2).
 *
 * <p>Para eventos {@code ORDER_*} a excecao carrega {@code orderId} e {@code eventType} ja
 * sanitizados (D-107, WR-03): o descarte continua, mas o WARN do listener diz de qual pedido e de
 * qual tipo era o evento. Nos demais casos os dois valem {@code null}. O payload bruto nunca viaja
 * na excecao (T-07-25).
 */
public class InvalidNotificationEventException extends RuntimeException {

    private final String orderId;
    private final String eventType;

    public InvalidNotificationEventException(String reason) {
        super(reason);
        this.orderId = null;
        this.eventType = null;
    }

    public InvalidNotificationEventException(String reason, Throwable cause) {
        super(reason, cause);
        this.orderId = null;
        this.eventType = null;
    }

    public InvalidNotificationEventException(String reason, String orderId, String eventType) {
        super(reason);
        this.orderId = orderId;
        this.eventType = eventType;
    }

    /** UUID do pedido, ou {@code ?} quando ausente ou nao-UUID; {@code null} fora do caminho ORDER_*. */
    public String getOrderId() {
        return orderId;
    }

    /** Tipo do evento, sanitizado para log; {@code null} fora do caminho ORDER_*. */
    public String getEventType() {
        return eventType;
    }
}
