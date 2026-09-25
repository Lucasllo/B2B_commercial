package com.orderflow.order.order.dto;

import com.orderflow.order.order.Order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Resumo do pedido usado por {@code GET /orders} (ORD-08/ORD-09, D-47) — nunca toca a coleção
 * {@code items}: a listagem não paga uma consulta extra por pedido, e o detalhe completo continua
 * em {@code GET /orders/{id}} ({@link OrderResponse}). {@code id} primeiro, mesmo critério do
 * {@code ORDER_RESPONSE_CONTRACT} (ver {@code 04-01-SUMMARY.md}).
 */
public record OrderSummaryResponse(
        UUID id,
        UUID companyId,
        String status,
        BigDecimal total,
        String createdBy,
        OffsetDateTime createdAt,
        String decidedBy,
        OffsetDateTime decidedAt) {

    public static OrderSummaryResponse from(Order order) {
        return new OrderSummaryResponse(
                order.getId(),
                order.getCompanyId(),
                order.getStatus().name(),
                order.getTotal(),
                order.getCreatedBy(),
                order.getCreatedAt(),
                order.getDecidedBy(),
                order.getDecidedAt());
    }
}
