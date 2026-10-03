package com.orderflow.order.order.dto;

import com.orderflow.order.order.OrderItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Linha do pedido, com preço e dados do produto congelados na criação.")
public record OrderItemResponse(
        @Schema(description = "Número da linha, a partir de 1", example = "1")
        int lineNumber,
        @Schema(description = "Produto do catálogo", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID productId,
        @Schema(description = "SKU do produto no momento da compra", example = "CAD-200")
        String sku,
        @Schema(description = "Nome do produto no momento da compra", example = "Caderno universitário 200 folhas")
        String name,
        @Schema(description = "Preço unitário congelado no momento da compra", example = "19.90")
        BigDecimal unitPrice,
        @Schema(description = "Quantidade pedida", example = "10")
        int quantity,
        @Schema(description = "unitPrice vezes quantity", example = "199.00")
        BigDecimal subtotal) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getLineNumber(),
                item.getProductId(),
                item.getSku(),
                item.getName(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getSubtotal());
    }
}
