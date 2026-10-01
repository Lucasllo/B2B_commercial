package com.orderflow.order.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orderflow.order.order.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Lado PRODUTOR do {@code NOTIFICATION_EVENT_CONTRACT} (D-78) — o evento de linha do tempo que o
 * order-service grava no outbox a cada transição persistida do pedido e que o notification-service
 * (06-04) guarda e serve em {@code GET /notifications/orders/{orderId}}. O contrato é o JSON
 * trocado pela fila, nunca o bytecode: o notification-service tem a sua própria cópia do DTO.
 *
 * <pre>
 * NOTIFICATION_EVENT_CONTRACT (envelope plano, campos nulos omitidos):
 * comuns: eventId, eventType, occurredAt (Instant ISO-8601), orderId, companyId
 * ORDER_CREATED          createdBy, total
 * ORDER_PENDING_APPROVAL (nenhum)
 * ORDER_APPROVED         decidedBy ("SYSTEM" na decisão automática), reason (opcional)
 * ORDER_REJECTED         decidedBy, reason
 * ORDER_CONFIRMED        carrier, trackingCode
 * ORDER_CANCELLED        cancellationCode, cancellationReason
 * ORDER_SHIPPED          shippedBy
 * ORDER_DELIVERED        deliveredBy
 * </pre>
 *
 * <p>{@code TIMELINE_OCCURRED_AT=transition-column}: {@code occurredAt} vem SEMPRE da coluna que a
 * própria transição gravou ({@code createdAt}, {@code decidedAt}, {@code confirmedAt}, {@code
 * cancelledAt}, {@code shippedAt}, {@code deliveredAt}), nunca de um {@code Instant.now()} à parte
 * — assim o evento de criação e o de decisão automática compartilham o mesmo instante e o
 * desempate por ciclo de vida do notification-service continua sendo exercitado de verdade. Coluna
 * nula significa chamada antes da transição: erro de programação, {@link IllegalStateException}.
 *
 * <p>Cada fábrica preenche só os campos do seu tipo (T-06-20): o resto fica nulo e {@code
 * NON_NULL} o omite do JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderLifecycleEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        UUID companyId,
        String createdBy,
        BigDecimal total,
        String decidedBy,
        String reason,
        String carrier,
        String trackingCode,
        String cancellationCode,
        String cancellationReason,
        String shippedBy,
        String deliveredBy) {

    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_PENDING_APPROVAL = "ORDER_PENDING_APPROVAL";
    public static final String ORDER_APPROVED = "ORDER_APPROVED";
    public static final String ORDER_REJECTED = "ORDER_REJECTED";
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String ORDER_DELIVERED = "ORDER_DELIVERED";

    /**
     * Lista explícita dos oito tipos (TIMELINE_ROUTING=explicit-list): o relay roteia por esta
     * lista, não por prefixo, para que um tipo inesperado continue sendo erro por evento.
     */
    public static final Set<String> EVENT_TYPES = Set.of(
            ORDER_CREATED, ORDER_PENDING_APPROVAL, ORDER_APPROVED, ORDER_REJECTED,
            ORDER_CONFIRMED, ORDER_CANCELLED, ORDER_SHIPPED, ORDER_DELIVERED);

    public static OrderLifecycleEvent created(Order order, UUID eventId) {
        return new OrderLifecycleEvent(eventId, ORDER_CREATED, instantOf(order.getCreatedAt(), "createdAt"),
                order.getId(), order.getCompanyId(), order.getCreatedBy(), order.getTotal(),
                null, null, null, null, null, null, null, null);
    }

    public static OrderLifecycleEvent pendingApproval(Order order, UUID eventId) {
        return new OrderLifecycleEvent(eventId, ORDER_PENDING_APPROVAL, instantOf(order.getCreatedAt(), "createdAt"),
                order.getId(), order.getCompanyId(),
                null, null, null, null, null, null, null, null, null, null);
    }

    public static OrderLifecycleEvent approved(Order order, UUID eventId) {
        return new OrderLifecycleEvent(eventId, ORDER_APPROVED, instantOf(order.getDecidedAt(), "decidedAt"),
                order.getId(), order.getCompanyId(),
                null, null, order.getDecidedBy(), order.getReason(), null, null, null, null, null, null);
    }

    public static OrderLifecycleEvent rejected(Order order, UUID eventId) {
        return new OrderLifecycleEvent(eventId, ORDER_REJECTED, instantOf(order.getDecidedAt(), "decidedAt"),
                order.getId(), order.getCompanyId(),
                null, null, order.getDecidedBy(), order.getReason(), null, null, null, null, null, null);
    }

    public static OrderLifecycleEvent confirmed(Order order, UUID eventId) {
        return new OrderLifecycleEvent(eventId, ORDER_CONFIRMED, instantOf(order.getConfirmedAt(), "confirmedAt"),
                order.getId(), order.getCompanyId(),
                null, null, null, null, order.getCarrier(), order.getTrackingCode(), null, null, null, null);
    }

    public static OrderLifecycleEvent cancelled(Order order, UUID eventId) {
        if (order.getCancellationCode() == null) {
            throw new IllegalStateException("Order " + order.getId() + " has no cancellationCode yet");
        }
        return new OrderLifecycleEvent(eventId, ORDER_CANCELLED, instantOf(order.getCancelledAt(), "cancelledAt"),
                order.getId(), order.getCompanyId(),
                null, null, null, null, null, null,
                order.getCancellationCode().name(), order.getCancellationReason(), null, null);
    }

    public static OrderLifecycleEvent shipped(Order order, UUID eventId) {
        return new OrderLifecycleEvent(eventId, ORDER_SHIPPED, instantOf(order.getShippedAt(), "shippedAt"),
                order.getId(), order.getCompanyId(),
                null, null, null, null, null, null, null, null, order.getShippedBy(), null);
    }

    public static OrderLifecycleEvent delivered(Order order, UUID eventId) {
        return new OrderLifecycleEvent(eventId, ORDER_DELIVERED, instantOf(order.getDeliveredAt(), "deliveredAt"),
                order.getId(), order.getCompanyId(),
                null, null, null, null, null, null, null, null, null, order.getDeliveredBy());
    }

    private static Instant instantOf(OffsetDateTime column, String columnName) {
        if (column == null) {
            throw new IllegalStateException("Order column " + columnName
                    + " is null - timeline event requested before the transition happened");
        }
        return column.toInstant();
    }
}
