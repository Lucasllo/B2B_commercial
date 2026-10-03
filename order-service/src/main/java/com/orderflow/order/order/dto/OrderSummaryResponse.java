package com.orderflow.order.order.dto;

import com.orderflow.order.order.Order;
import com.orderflow.order.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Resumo do pedido usado por {@code GET /orders} (ORD-08/ORD-09, D-47) — nunca toca a coleção
 * {@code items}: a listagem não paga uma consulta extra por pedido, e o detalhe completo continua
 * em {@code GET /orders/{id}} ({@link OrderResponse}). {@code id} primeiro, mesmo critério do
 * {@code ORDER_RESPONSE_CONTRACT} (ver {@code 04-01-SUMMARY.md}).
 */
@Schema(description = "Resumo do pedido usado na listagem, sem itens.")
public record OrderSummaryResponse(
        @Schema(description = "Identificador do pedido", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        UUID id,
        @Schema(description = "Empresa compradora", example = "1b4e28ba-2fa1-11d2-883f-0016d3cca427")
        UUID companyId,
        @Schema(description = "Status do pedido. O campo é texto; os valores válidos são os de OrderStatus. Transições: CREATED vai a PENDING_APPROVAL ou APPROVED; PENDING_APPROVAL a APPROVED ou REJECTED; APPROVED a RESERVING; RESERVING a CONFIRMED ou CANCELLED; CONFIRMED a SHIPPED; SHIPPED a DELIVERED. REJECTED, CANCELLED e DELIVERED são terminais.", example = "RESERVING", implementation = OrderStatus.class)
        String status,
        @Schema(description = "Total do pedido com duas casas decimais", example = "199.00")
        BigDecimal total,
        @Schema(description = "Sub do usuário que criou o pedido", example = "buyer@acme.com")
        String createdBy,
        @Schema(description = "Instante de criação", example = "2026-01-15T12:00:00Z")
        OffsetDateTime createdAt,
        @Schema(description = "Quem decidiu; nulo enquanto não decidido", example = "seller@orderflow.com")
        String decidedBy,
        @Schema(description = "Instante da decisão; nulo enquanto não decidido", example = "2026-01-15T12:05:00Z")
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
