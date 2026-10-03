package com.orderflow.order.order.dto;

import com.orderflow.order.order.Order;
import com.orderflow.order.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(description = "Pedido completo, com itens e dados de decisão, saga e expedição.")
public record OrderResponse(
        @Schema(description = "Identificador do pedido", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        UUID id,
        @Schema(description = "Empresa compradora, derivada do JWT na criação", example = "1b4e28ba-2fa1-11d2-883f-0016d3cca427")
        UUID companyId,
        @Schema(description = "Status do pedido. O campo é texto; os valores válidos são os de OrderStatus. Transições: CREATED vai a PENDING_APPROVAL ou APPROVED; PENDING_APPROVAL a APPROVED ou REJECTED; APPROVED a RESERVING; RESERVING a CONFIRMED ou CANCELLED; CONFIRMED a SHIPPED; SHIPPED a DELIVERED. REJECTED, CANCELLED e DELIVERED são terminais.", example = "RESERVING", implementation = OrderStatus.class)
        String status,
        @Schema(description = "Total do pedido, soma dos subtotais, com duas casas decimais", example = "199.00")
        BigDecimal total,
        @Schema(description = "Subject (sub) do usuário que criou o pedido", example = "buyer@acme.com")
        String createdBy,
        @Schema(description = "Instante de criação", example = "2026-01-15T12:00:00Z")
        OffsetDateTime createdAt,
        @Schema(description = "Quem decidiu: sub do vendedor, ou o decisor automático; nulo enquanto não decidido", example = "seller@orderflow.com")
        String decidedBy,
        @Schema(description = "Instante da decisão; nulo enquanto não decidido", example = "2026-01-15T12:05:00Z")
        OffsetDateTime decidedAt,
        @Schema(description = "Motivo informado na decisão manual; pode ser nulo", example = "Limite de crédito excedido")
        String reason,
        @Schema(description = "Código do cancelamento pela saga (INSUFFICIENT_STOCK, PRODUCT_NOT_STOCKED, RESERVATION_CANCELLED ou RESERVATION_TIMEOUT); nulo se o pedido não foi cancelado", example = "INSUFFICIENT_STOCK")
        String cancellationCode,
        @Schema(description = "Descrição do cancelamento; nulo se o pedido não foi cancelado", example = "Estoque insuficiente para um dos itens")
        String cancellationReason,
        @Schema(description = "Instante da confirmação (estoque reservado); nulo antes disso", example = "2026-01-15T12:06:00Z")
        OffsetDateTime confirmedAt,
        @Schema(description = "Instante do cancelamento; nulo se não cancelado", example = "2026-01-15T12:06:00Z")
        OffsetDateTime cancelledAt,
        @Schema(description = "Transportadora atribuída ao confirmar; nulo antes da confirmação", example = "Transportadora Atlas")
        String carrier,
        @Schema(description = "Código de rastreio atribuído ao confirmar; nulo antes da confirmação", example = "OF-1A2B3C4D")
        String trackingCode,
        @Schema(description = "Instante da expedição; nulo antes de SHIPPED", example = "2026-01-16T09:00:00Z")
        OffsetDateTime shippedAt,
        @Schema(description = "Sub do vendedor que expediu; nulo antes de SHIPPED", example = "seller@orderflow.com")
        String shippedBy,
        @Schema(description = "Instante da entrega; nulo antes de DELIVERED", example = "2026-01-18T15:30:00Z")
        OffsetDateTime deliveredAt,
        @Schema(description = "Sub do vendedor que registrou a entrega; nulo antes de DELIVERED", example = "seller@orderflow.com")
        String deliveredBy,
        @Schema(description = "Itens do pedido, na ordem de lineNumber")
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
