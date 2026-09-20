package com.orderflow.catalog.product.dto;

import com.orderflow.catalog.product.ProductStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Payload de {@code PUT /products/{productId}/status} — único caminho de retirada/reativação do
 * catálogo (D-23). Tipar o componente como o enum {@link ProductStatus} faz um valor desconhecido
 * virar erro de desserialização tratado como 400 pelo handler de corpo malformado, em vez de
 * silenciosamente virar nulo.
 */
public record UpdateProductStatusRequest(
        @NotNull ProductStatus status
) {
}
