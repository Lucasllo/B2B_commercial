package com.orderflow.catalog.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Payload de atualização. SKU e status não mudam por este caminho.")
public record UpdateProductRequest(
        @Schema(description = "Nome exibido para o comprador", example = "Caderno universitário 200 folhas")
        @NotBlank @Size(max = 255) String name,
        @Schema(description = "Descrição livre, opcional", example = "Capa dura, pauta universitária")
        @Size(max = 1000) String description,
        @Schema(description = "Preço unitário com duas casas decimais", example = "21.50")
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal price
) {
}
