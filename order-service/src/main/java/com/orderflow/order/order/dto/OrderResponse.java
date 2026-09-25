package com.orderflow.order.order.dto;

import com.orderflow.order.order.Order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code id} vem primeiro — o script de smoke do plano {@code 04-05} extrai o id do começo do
 * corpo. Campos: {@code id}, {@code companyId}, {@code status}, {@code total}, {@code createdBy},
 * {@code createdAt}, {@code decidedBy}, {@code decidedAt}, {@code reason}, {@code items}
 * (ORDER_RESPONSE_CONTRACT, ver SUMMARY).
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
                order.getItems().stream().map(OrderItemResponse::from).toList());
    }
}
