package com.orderflow.order.order.exception;

import java.util.List;
import java.util.UUID;

/**
 * Lançada quando um ou mais {@code productId} do pedido não correspondem a um produto {@code
 * ACTIVE} disponível no catalog-service (D-44) — nenhum pedido é criado. A ordem da lista é a
 * ordem em que os itens apareceram no pedido.
 */
public class InvalidOrderItemsException extends RuntimeException {

    private final List<UUID> productIds;

    public InvalidOrderItemsException(List<UUID> productIds) {
        super("One or more items are invalid or unavailable");
        this.productIds = List.copyOf(productIds);
    }

    public List<UUID> getProductIds() {
        return productIds;
    }
}
