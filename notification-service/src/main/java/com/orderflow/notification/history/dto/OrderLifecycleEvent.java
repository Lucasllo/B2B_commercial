package com.orderflow.notification.history.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lado consumidor do NOTIFICATION_EVENT_CONTRACT dos eventos de pedido (D-78, D-82). Duplica por
 * contrato JSON o envelope que o {@code order-service} publica na {@code notification-events-queue}
 * — sem modulo compartilhado (mesma regra de D-62). Envelope plano, campos nulos omitidos:
 *
 * <pre>{@code
 * {"eventId":"<uuid>","eventType":"ORDER_CONFIRMED","occurredAt":"2026-09-30T12:00:00.123456Z",
 *  "orderId":"<uuid>","companyId":"<uuid>","carrier":"Expresso Cerrado","trackingCode":"AB123456789BR"}
 * }</pre>
 *
 * <p>Alem de {@code eventId}, {@code eventType}, {@code occurredAt}, {@code orderId} e
 * {@code companyId}, cada tipo exige:
 * <ul>
 *   <li>{@code ORDER_CREATED}: {@code createdBy} (ate 64), {@code total} (numero &gt;= 0)</li>
 *   <li>{@code ORDER_PENDING_APPROVAL}: nenhum</li>
 *   <li>{@code ORDER_APPROVED}: {@code decidedBy} (ate 64); {@code reason} opcional (ate 500)</li>
 *   <li>{@code ORDER_REJECTED}: {@code decidedBy} (ate 64), {@code reason} (ate 500)</li>
 *   <li>{@code ORDER_CONFIRMED}: {@code carrier} (ate 64), {@code trackingCode}
 *       ({@code ^[A-Z]{2}[0-9]{9}BR$})</li>
 *   <li>{@code ORDER_CANCELLED}: {@code cancellationCode} ({@code ^[A-Z_]{1,40}$}),
 *       {@code cancellationReason} (ate 500)</li>
 *   <li>{@code ORDER_SHIPPED}: {@code shippedBy} (ate 64)</li>
 *   <li>{@code ORDER_DELIVERED}: {@code deliveredBy} (ate 64)</li>
 * </ul>
 * {@code decidedBy} vale {@code SYSTEM} na aprovacao automatica.
 *
 * <p>Os campos de texto sao {@link String} e os numericos {@link BigDecimal}, todos anulaveis de
 * proposito: o consumidor nao confia no produtor, e um campo ausente precisa ser detectado como
 * evento invalido, nao virar valor padrao em silencio.
 */
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
        String deliveredBy
) {
    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_PENDING_APPROVAL = "ORDER_PENDING_APPROVAL";
    public static final String ORDER_APPROVED = "ORDER_APPROVED";
    public static final String ORDER_REJECTED = "ORDER_REJECTED";
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String ORDER_DELIVERED = "ORDER_DELIVERED";

    /** Os oito tipos aceitos, na ordem do ciclo de vida do pedido. */
    public static final List<String> TYPES = List.of(
            ORDER_CREATED,
            ORDER_PENDING_APPROVAL,
            ORDER_APPROVED,
            ORDER_REJECTED,
            ORDER_CONFIRMED,
            ORDER_CANCELLED,
            ORDER_SHIPPED,
            ORDER_DELIVERED);
}
