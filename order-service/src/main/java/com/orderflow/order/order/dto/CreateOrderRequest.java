package com.orderflow.order.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Nenhum campo de preço, total ou empresa — um {@code companyId} ou {@code price} extra no corpo
 * é ignorado ({@link JsonIgnoreProperties}). O {@code companyId} do pedido vem só do claim {@code
 * company_id} do JWT (Claude's Discretion resolvida em {@code OrderController}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateOrderRequest(
        @NotEmpty @Size(max = MAX_ITEMS_PER_ORDER) List<@Valid @NotNull OrderItemRequest> items) {

    /**
     * Cada item vira uma chamada síncrona ao catalog-service — o limite é também a defesa contra
     * um pedido que amplifica uma requisição em milhares de chamadas de saída (Claude's Discretion,
     * T-04-11, 04-02 Task 3; nenhuma decisão do contexto fixa limites).
     */
    public static final int MAX_ITEMS_PER_ORDER = 50;
}
