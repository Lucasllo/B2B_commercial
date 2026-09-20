package com.orderflow.catalog.product.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
public record CreateProductRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1000) String description,
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal price
) {
}
