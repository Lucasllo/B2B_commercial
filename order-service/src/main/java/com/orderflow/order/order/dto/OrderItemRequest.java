package com.orderflow.order.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Item do pedido: produto do catálogo e quantidade.")
public record OrderItemRequest(
        @Schema(description = "Identificador do produto no catálogo", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        @NotNull UUID productId,
        @Schema(description = "Quantidade pedida, de 1 a 1.000.000", example = "10")
        @NotNull @Positive @Max(MAX_QUANTITY_PER_ITEM) Integer quantity) {

    /**
     * Defesa contra abuso de quantidade (Claude's Discretion, 04-02 Task 3; nenhuma decisão do
     * contexto fixa limites) — um valor absurdamente alto não tem efeito prático além de estressar
     * o cálculo de subtotal/total sem ganho real para um pedido legítimo.
     */
    public static final int MAX_QUANTITY_PER_ITEM = 1_000_000;
}
