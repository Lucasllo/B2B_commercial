package com.orderflow.order.saga.messaging.dto;

import com.orderflow.order.order.Order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comando de BAIXA FÍSICA enviado à {@code inventory-commands-queue} quando o vendedor expede um
 * pedido (D-75) — o inventory-service converte a reserva do pedido em saída de {@code
 * quantity_on_hand}. Mesmo envelope de {@link ReserveStockCommand}/{@link ReleaseStockCommand},
 * mesmo {@code reservationId = orderId.toString()}, mas SEM {@code reason}:
 *
 * <pre>
 * SAGA_MESSAGE_CONTRACT (ShipStock):
 * {"eventId":"&lt;uuid&gt;","eventType":"ShipStock","occurredAt":"&lt;instant ISO-8601&gt;",
 *  "orderId":"&lt;uuid&gt;","reservationId":"&lt;orderId em texto&gt;",
 *  "items":[{"productId":"&lt;uuid&gt;","quantity":&lt;int&gt;}]}
 * </pre>
 *
 * <p>Os itens saem na ordem de {@code lineNumber}. O pedido não espera resposta e não existe estado
 * intermediário (sem {@code SHIPPING}): a baixa é assíncrona, então por alguns segundos o pedido já
 * está {@code SHIPPED} e o estoque ainda mostra a reserva. Este DTO é duplicado por contrato JSON
 * no inventory-service (D-62) — o contrato é o JSON trocado pela fila, nunca o bytecode.
 */
public record ShipStockCommand(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String reservationId,
        List<ReservationLine> items) {

    public static final String EVENT_TYPE = "ShipStock";

    /**
     * Usa {@link Order#getItems()} na ordem de {@code lineNumber} (já garantida pelo {@code
     * @OrderBy} da entidade) e {@code reservationId = order.getId().toString()}.
     */
    public static ShipStockCommand from(Order order, UUID eventId, Instant occurredAt) {
        List<ReservationLine> lines = order.getItems().stream()
                .map(item -> new ReservationLine(item.getProductId(), item.getQuantity()))
                .toList();
        return new ShipStockCommand(
                eventId, EVENT_TYPE, occurredAt, order.getId(), order.getId().toString(), lines);
    }
}
