package com.orderflow.catalog.product.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Payload de criação de produto (CAT-01). Não declara nenhum campo de status — o status nasce
 * sempre {@code ACTIVE}, fixado no servidor por {@code ProductService} (defesa contra mass
 * assignment, mesma mitigação já provada em {@code CreateCompanyRequest}/{@code CompanyService}
 * com {@code Role.BUYER}). {@code @JsonIgnoreProperties} garante que um campo extra de status
 * enviado pelo cliente é silenciosamente ignorado na desserialização, em vez de derrubar a
 * requisição inteira com um erro de parsing.
 *
 * <p>{@code @Digits(fraction = 2)} é o mecanismo que rejeita (400) em vez de arredondar um preço
 * com mais de 2 casas decimais — implementação direta da primeira proibição registrada em
 * {@code must_haves.prohibitions} do plano 02-01.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Payload de criação de produto. O status nasce ACTIVE no servidor; um campo status extra é ignorado.")
public record CreateProductRequest(
        @Schema(description = "Código único do produto no catálogo", example = "CAD-200")
        @NotBlank @Size(max = 64) String sku,
        @Schema(description = "Nome exibido para o comprador", example = "Caderno universitário 200 folhas")
        @NotBlank @Size(max = 255) String name,
        @Schema(description = "Descrição livre, opcional", example = "Capa dura, pauta universitária")
        @Size(max = 1000) String description,
        @Schema(description = "Preço unitário com duas casas decimais", example = "19.90")
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal price
) {
}
