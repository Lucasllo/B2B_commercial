package com.orderflow.inventory.stock.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Reserva uma quantidade de estoque, de forma idempotente por reservationId.")
public record ReserveStockRequest(
        @Schema(description = "Identificador da reserva escolhido pelo chamador, até 255 caracteres, único por produto. Repetir o mesmo valor não reserva de novo",
                example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        @NotBlank @Size(max = 255) String reservationId,
        @Schema(description = "Quantidade a reservar, maior que zero", example = "10")
        @NotNull @Positive Integer quantity
) {
}
