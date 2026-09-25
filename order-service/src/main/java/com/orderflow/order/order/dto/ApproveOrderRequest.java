package com.orderflow.order.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

/**
 * Corpo opcional de {@code POST /orders/{id}/approve} (ORD-03, D-46) — {@code reason} é opcional;
 * motivo em branco é gravado como nulo (ver {@link com.orderflow.order.order.Order#approveManually}).
 * {@code MAX_REASON_LENGTH} alinhado à coluna {@code reason VARCHAR(500)}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApproveOrderRequest(@Size(max = MAX_REASON_LENGTH) String reason) {

    public static final int MAX_REASON_LENGTH = 500;
}
