package com.orderflow.order.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderItemRequest(
        @NotNull UUID productId,
        @NotNull @Positive @Max(MAX_QUANTITY_PER_ITEM) Integer quantity) {

    /**
     * Defesa contra abuso de quantidade (Claude's Discretion, 04-02 Task 3; nenhuma decisão do
     * contexto fixa limites) — um valor absurdamente alto não tem efeito prático além de estressar
     * o cálculo de subtotal/total sem ganho real para um pedido legítimo.
     */
    public static final int MAX_QUANTITY_PER_ITEM = 1_000_000;
}
