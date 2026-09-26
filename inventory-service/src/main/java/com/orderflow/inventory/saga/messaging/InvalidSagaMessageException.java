package com.orderflow.inventory.saga.messaging;

/**
 * Lançada por {@link SagaCommandParser} quando o corpo da mensagem da {@code
 * inventory-commands-queue} não pode virar um comando válido — corpo acima do teto de tamanho,
 * JSON inválido, conteúdo depois do valor JSON, raiz que não é objeto, {@code eventType}
 * desconhecido ou ausente, campo obrigatório ausente, {@code reservationId} diferente do
 * {@code orderId}, {@code items} vazio ou acima do teto, {@code productId} nulo ou repetido, ou
 * {@code quantity} fora da faixa permitida (D-67, ASVS V5).
 *
 * <p>{@link ReservationCommandListener} captura apenas esta exceção para descartar a mensagem com
 * log WARN — qualquer outra exceção (banco fora, conflito esgotado) propaga para o SQS reentregar
 * até a DLQ, porque só esta representa uma mensagem que nunca poderia ser processada.
 */
public class InvalidSagaMessageException extends RuntimeException {

    public InvalidSagaMessageException(String message) {
        super(message);
    }
}
