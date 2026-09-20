package com.orderflow.catalog.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Payload de {@code PUT /products/{productId}}. As mesmas restrições de escala monetária de
 * {@link CreateProductRequest} (D-06) — o caminho de atualização não é uma porta dos fundos com
 * validação mais frouxa que o caminho de criação. Sem campo de {@code sku} (imutável) e sem
 * campo de status (a retirada/reativação é feita por {@code PUT /products/{productId}/status}).
 */
public record UpdateProductRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1000) String description,
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal price
) {
}
