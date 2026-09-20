package com.orderflow.catalog.product;

/**
 * Estados suportados pelo catálogo (D-23, PRODUCT_STATUS_CONTRACT=status-two-values). A retirada
 * de um produto do catálogo é sempre uma transição para {@code DISCONTINUED} via
 * {@code PUT /products/{productId}/status} — nunca uma remoção física da linha. A ordem aqui
 * espelha a ordem do {@code CHECK} da migração V1.
 */
public enum ProductStatus {
    ACTIVE,
    DISCONTINUED
}
