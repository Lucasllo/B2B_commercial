package com.orderflow.catalog.product.dto;

import com.orderflow.catalog.product.Product;
import com.orderflow.catalog.product.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Corpo de resposta de {@code POST}/{@code GET}/{@code PUT /products}. {@code status} é exposto
 * como texto (nome do enum), nunca a posição ordinal.
 */
@Schema(description = "Produto do catálogo.")
public record ProductResponse(
        @Schema(description = "Identificador do produto", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,
        @Schema(description = "Código único do produto", example = "CAD-200")
        String sku,
        @Schema(description = "Nome exibido para o comprador", example = "Caderno universitário 200 folhas")
        String name,
        @Schema(description = "Descrição livre", example = "Capa dura, pauta universitária")
        String description,
        @Schema(description = "Preço unitário com duas casas decimais", example = "19.90")
        BigDecimal price,
        @Schema(description = "Status do produto. O campo é texto; os valores válidos são os de ProductStatus.",
                example = "ACTIVE", implementation = ProductStatus.class)
        String status,
        @Schema(description = "Instante de criação", example = "2026-01-15T12:00:00Z")
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
