package com.orderflow.inventory.saga.messaging;

/**
 * Lançada por {@link SagaCommandParser} quando um {@code ShipStock} da {@code inventory-commands-queue}
 * não pode virar um comando válido (D-107, WR-01 da Fase 6).
 *
 * <p>{@code ShipStock} só existe para um pedido que já está SHIPPED: descartá-lo com WARN (política
 * D-67 dos outros comandos) perderia a baixa física de estoque sem deixar rastro. Por isso este
 * subtipo marca o caso como <b>anomalia técnica</b> — {@link ReservationCommandListener} o captura
 * antes da captura genérica de {@link InvalidSagaMessageException}, loga ERROR e o relança, o SQS
 * reentrega e a mensagem termina na {@code inventory-commands-dlq} após {@code maxReceiveCount}
 * recebimentos. {@code ReserveStock} e {@code ReleaseStock} inválidos seguem descartados com WARN (D-67).
 *
 * <p>{@link #getOrderIdForLog()} já vem sanitizado (sem caracteres de controle, no máximo 64
 * caracteres; {@code ?} quando o corpo não trouxe {@code orderId}) — nunca o payload cru (T-05-03).
 */
public class InvalidShipStockException extends InvalidSagaMessageException {

    private final String orderIdForLog;

    public InvalidShipStockException(String message, String orderIdForLog) {
        super(message);
        this.orderIdForLog = orderIdForLog;
    }

    public String getOrderIdForLog() {
        return orderIdForLog;
    }
}
