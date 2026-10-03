package com.orderflow.order.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo obrigatório de {@code POST /orders/{id}/reject} (ORD-03, D-37, D-46) — {@code reason} é
 * obrigatório, ao contrário de {@link ApproveOrderRequest}; sem corpo o
 * {@code GlobalExceptionHandler} já devolve {@code malformed_request}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Corpo obrigatório da rejeição manual.")
public record RejectOrderRequest(
        @Schema(description = "Motivo da rejeição, obrigatório, até 500 caracteres",
                example = "Limite de crédito excedido")
        @NotBlank @Size(max = ApproveOrderRequest.MAX_REASON_LENGTH) String reason) {
}
