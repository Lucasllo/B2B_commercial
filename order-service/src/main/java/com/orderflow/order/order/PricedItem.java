package com.orderflow.order.order;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Item já validado e precificado no catalog-service, sem nada de JPA — atravessa a fronteira
 * entre {@link OrderCreationService} (não transacional, faz as chamadas HTTP) e {@link
 * OrderService} (transacional, decide e persiste), D-41.
 */
public record PricedItem(
        int lineNumber,
        UUID productId,
        String sku,
        String name,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal subtotal) {
}
