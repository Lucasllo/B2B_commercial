package com.orderflow.inventory.saga.messaging.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comando recebido da fila {@code inventory-commands-queue} pedindo a reserva de todos os itens de
 * um pedido, tudo ou nada (D-55) — {@code SAGA_MESSAGE_CONTRACT} registrado em
 * {@code 05-01-SUMMARY.md}. Envelope plano: primeiro {@code eventId} (igual ao id da linha do
 * outbox do order-service que o originou), {@code eventType}, {@code occurredAt}, depois os campos
 * do tipo.
 *
 * <p>{@code reservationId} é sempre {@code orderId.toString()} (D-55,
 * RESERVATION_ID_SCOPE=scope-per-product) — usado como par (productId, reservationId) na chave de
 * idempotência de {@code stock_reservations}.
 *
 * <p>Este DTO é <b>duplicado</b> por contrato JSON do order-service (D-62, sem módulo
 * compartilhado, mesmo motivo do {@code TestJwt} copiado entre módulos) — o contrato é o JSON
 * trocado pela fila, nunca o bytecode desta classe. Validado por {@link
 * com.orderflow.inventory.saga.messaging.SagaCommandParser} antes de qualquer uso — este record em
 * si não valida nada.
 */
public record ReserveStockCommand(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String reservationId,
        List<ReservationLine> items) {

    public static final String EVENT_TYPE = "ReserveStock";
}
