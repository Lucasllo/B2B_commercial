package com.orderflow.order.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Corpo opcional de {@code POST /orders/{id}/approve} (ORD-03, D-46) — {@code reason} é opcional;
 * motivo em branco é gravado como nulo (ver {@link com.orderflow.order.order.Order#approveManually}).
 * {@code MAX_REASON_LENGTH} alinhado à coluna {@code reason VARCHAR(500)}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Corpo opcional da aprovação manual.")
public record ApproveOrderRequest(
        @Schema(description = "Motivo da aprovação, opcional, até 500 caracteres; em branco é gravado como nulo",
                example = "Cliente com histórico de pagamento em dia")
        @Size(max = MAX_REASON_LENGTH) String reason) {

    public static final int MAX_REASON_LENGTH = 500;
}
