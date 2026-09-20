package com.orderflow.catalog.product.dto;

import com.orderflow.catalog.product.Product;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Corpo de resposta de {@code POST}/{@code GET}/{@code PUT /products}. {@code status} é exposto
 * como texto (nome do enum), nunca a posição ordinal.
 */
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        String status,
        OffsetDateTime createdAt
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStatus().name(),
                product.getCreatedAt());
    }
}
