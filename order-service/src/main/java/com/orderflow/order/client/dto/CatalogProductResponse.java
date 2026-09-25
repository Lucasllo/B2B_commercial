package com.orderflow.order.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Espelha por contrato JSON {@code com.orderflow.catalog.product.dto.ProductResponse} do
 * catalog-service — nunca importado diretamente (ARCHITECTURE.md, anti-padrão de compartilhar
 * classes entre serviços). Campos extras da resposta real ({@code description}, {@code
 * createdAt}) são ignorados por {@link JsonIgnoreProperties}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogProductResponse(UUID id, String sku, String name, BigDecimal price, String status) {
}
