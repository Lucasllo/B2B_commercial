package com.orderflow.inventory.stock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Payload de {@code POST /inventory/{productId}/reservations}. {@code reservationId} e o
 * identificador de idempotencia fornecido pelo chamador (D-11) — string de ate 255 caracteres,
 * unica por par {@code (productId, reservationId)} (RESERVATION_ID_SCOPE=scope-per-product,
 * Task 1 deste plano). Este e exatamente o mecanismo que o consumidor SQS da Fase 5 vai usar para
 * sobreviver a reentrega de evento (ORD-06, TEST-03) — a Fase 5 deve gerar identificadores
 * opacos (UUID) para evitar colisao entre pedidos diferentes que reservem o mesmo produto.
 */
public record ReserveStockRequest(
        @NotBlank @Size(max = 255) String reservationId,
        @NotNull @Positive Integer quantity
) {
}
