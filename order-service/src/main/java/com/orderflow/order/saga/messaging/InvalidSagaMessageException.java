package com.orderflow.order.saga.messaging;

/**
 * Lançada por {@link SagaEventParser} quando o corpo da mensagem da {@code order-events-queue} não
 * pode virar um evento válido — corpo acima do teto de tamanho, JSON inválido, conteúdo depois do
 * valor JSON, raiz que não é objeto, {@code eventType} desconhecido ou ausente, campo obrigatório
 * ausente, {@code reservationId} diferente do {@code orderId}, {@code reasonCode} fora dos valores
 * permitidos, ou um item/falha com campo fora da faixa permitida (D-67, ASVS V5).
 *
 * <p>{@link ReservationResultListener} captura apenas esta exceção para descartar a mensagem com
 * log WARN — qualquer outra exceção propaga para o SQS reentregar até a DLQ, porque só esta
 * representa uma mensagem que nunca poderia ser processada (mesma disciplina de {@code
 * SagaCommandParser}/{@code InvalidSagaMessageException} do inventory-service, D-67).
 */
public class InvalidSagaMessageException extends RuntimeException {

    public InvalidSagaMessageException(String message) {
        super(message);
    }
}
