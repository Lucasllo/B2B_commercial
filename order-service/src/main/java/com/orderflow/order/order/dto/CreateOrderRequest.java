package com.orderflow.order.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Nenhum campo de preço, total ou empresa — um {@code companyId} ou {@code price} extra no corpo
 * é ignorado ({@link JsonIgnoreProperties}). O {@code companyId} do pedido vem só do claim {@code
 * company_id} do JWT (Claude's Discretion resolvida em {@code OrderController}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateOrderRequest(
        @NotEmpty List<@Valid @NotNull OrderItemRequest> items) {
}
