package com.orderflow.order.order.dto;

import com.orderflow.order.order.OrderItem;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
        int lineNumber,
        UUID productId,
        String sku,
        String name,
        BigDecimal unitPrice,
        int quantity,
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
