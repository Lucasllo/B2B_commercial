package com.orderflow.catalog.product.dto;

import com.orderflow.catalog.product.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Payload de {@code PUT /products/{productId}/status} — único caminho de retirada/reativação do
 * catálogo (D-23). Tipar o componente como o enum {@link ProductStatus} faz um valor desconhecido
 * virar erro de desserialização tratado como 400 pelo handler de corpo malformado, em vez de
 * silenciosamente virar nulo.
 */
@Schema(description = "Único caminho de retirada ou reativação do produto.")
public record UpdateProductStatusRequest(
        @Schema(description = "Novo status do produto", example = "DISCONTINUED", implementation = ProductStatus.class)
        @NotNull ProductStatus status
) {
}
