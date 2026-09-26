package com.orderflow.order.saga.messaging.dto;

import com.orderflow.order.order.Order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comando enviado pelo order-service à fila {@code inventory-commands-queue} pedindo a reserva de
 * todos os itens de um pedido, tudo ou nada (D-55) — {@code SAGA_MESSAGE_CONTRACT} registrado no
 * SUMMARY desta fase. Envelope plano, mesma convenção do {@code StockAdjustedEvent}/
 * NOTIFICATION_EVENT_CONTRACT da Fase 3: primeiro {@code eventId} (igual ao id da linha do outbox
 * que o originou), {@code eventType}, {@code occurredAt}, depois os campos do tipo.
 *
 * <p>{@code reservationId} é sempre {@code orderId.toString()} (D-55,
 * RESERVATION_ID_SCOPE=scope-per-product herdado da Fase 2) — o inventory-service usa o par
 * (productId, reservationId) como chave de idempotência, nunca um id de reserva por produto.
 *
 * <p>Este DTO é <b>duplicado</b> por contrato JSON no inventory-service (D-62, sem módulo
 * compartilhado) — o contrato é o JSON trocado pela fila, nunca o bytecode desta classe.
 */
public record ReserveStockCommand(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String reservationId,
        List<ReservationLine> items) {

    public static final String EVENT_TYPE = "ReserveStock";

    /**
     * Usa {@link Order#getItems()} na ordem de {@code lineNumber} (já garantida pelo
     * {@code @OrderBy} da entidade) e {@code reservationId = order.getId().toString()} (D-55).
     */
    public static ReserveStockCommand from(Order order, UUID eventId, Instant occurredAt) {
        List<ReservationLine> lines = order.getItems().stream()
                .map(item -> new ReservationLine(item.getProductId(), item.getQuantity()))
                .toList();
        return new ReserveStockCommand(
                eventId, EVENT_TYPE, occurredAt, order.getId(), order.getId().toString(), lines);
    }
}
