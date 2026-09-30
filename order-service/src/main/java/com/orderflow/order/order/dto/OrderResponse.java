package com.orderflow.order.order.dto;

import com.orderflow.order.order.Order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code id} vem primeiro — o script de smoke do plano {@code 04-05} extrai o id do começo do
 * corpo. Campos: {@code id}, {@code companyId}, {@code status}, {@code total}, {@code createdBy},
 * {@code createdAt}, {@code decidedBy}, {@code decidedAt}, {@code reason}, {@code
 * cancellationCode}, {@code cancellationReason}, {@code confirmedAt}, {@code cancelledAt}, {@code
 * carrier}, {@code trackingCode}, {@code shippedAt}, {@code shippedBy}, {@code deliveredAt},
 * {@code deliveredBy}, {@code items}.
 *
 * <p>ORDER_RESPONSE_CONTRACT=id,companyId,status,total,createdBy,createdAt,decidedBy,decidedAt,
 * reason,cancellationCode,cancellationReason,confirmedAt,cancelledAt,carrier,trackingCode,
 * shippedAt,shippedBy,deliveredAt,deliveredBy,items (atualizado em 06-01, D-73/D-76 — os seis
 * campos de expedição entram depois de {@code cancelledAt} e antes de {@code items}; pedidos ainda
 * não confirmados os devolvem nulos). Os campos da saga vieram em 05-03.
 */
public record OrderResponse(
        UUID id,
        UUID companyId,
        String status,
        BigDecimal total,
        String createdBy,
        OffsetDateTime createdAt,
        String decidedBy,
        OffsetDateTime decidedAt,
        String reason,
        String cancellationCode,
        String cancellationReason,
        OffsetDateTime confirmedAt,
        OffsetDateTime cancelledAt,
        String carrier,
        String trackingCode,
        OffsetDateTime shippedAt,
        String shippedBy,
        OffsetDateTime deliveredAt,
        String deliveredBy,
        List<OrderItemResponse> items) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCompanyId(),
                order.getStatus().name(),
                order.getTotal(),
                order.getCreatedBy(),
                order.getCreatedAt(),
                order.getDecidedBy(),
                order.getDecidedAt(),
                order.getReason(),
                order.getCancellationCode() == null ? null : order.getCancellationCode().name(),
                order.getCancellationReason(),
                order.getConfirmedAt(),
                order.getCancelledAt(),
                order.getCarrier(),
                order.getTrackingCode(),
                order.getShippedAt(),
                order.getShippedBy(),
                order.getDeliveredAt(),
                order.getDeliveredBy(),
                order.getItems().stream().map(OrderItemResponse::from).toList());
    }
}
