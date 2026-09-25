package com.orderflow.order.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo obrigatório de {@code POST /orders/{id}/reject} (ORD-03, D-37, D-46) — {@code reason} é
 * obrigatório, ao contrário de {@link ApproveOrderRequest}; sem corpo o
 * {@code GlobalExceptionHandler} já devolve {@code malformed_request}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RejectOrderRequest(@NotBlank @Size(max = ApproveOrderRequest.MAX_REASON_LENGTH) String reason) {
}
