package com.orderflow.notification.history.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.orderflow.notification.history.NotificationRecord;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Corpo de resposta de {@code GET /notifications/{productId}} e de
 * {@code GET /notifications/orders/{orderId}}; {@code entityId} e o id do produto ou do pedido
 * (D-80). O {@code payload} sai como objeto
 * JSON aninhado, nao como texto escapado, para que quem abrir o endpoint pela Swagger UI leia o
 * evento original sem decodificar nada.
 */
public record NotificationResponse(
        @Schema(description = "Identificador do produto ou do pedido a que o evento se refere", example = "3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b")
        String entityId,
        @Schema(description = "Identificador único do evento", example = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d")
        String eventId,
        @Schema(description = "Tipo do evento (STOCK_ADJUSTED ou ORDER_*)", example = "ORDER_CONFIRMED")
        String eventType,
        @Schema(description = "Mensagem legível montada pelo servidor", example = "Pedido confirmado — transportadora Expresso Cerrado, rastreio AB123456789BR")
        String message,
        @Schema(description = "Evento original como objeto JSON aninhado", type = "object")
        JsonNode payload,
        @Schema(description = "Instante em que o evento ocorreu na origem", example = "2026-09-30T12:00:00Z")
        Instant occurredAt,
        @Schema(description = "Instante em que o histórico gravou o evento", example = "2026-09-30T12:00:01.250Z")
        Instant recordedAt
) {

    public static NotificationResponse from(NotificationRecord record, JsonNode payload) {
        return new NotificationResponse(
                record.getEntityId(),
                record.getEventId(),
                record.getEventType(),
                record.getMessage(),
                payload,
                record.getOccurredAt(),
                record.getRecordedAt());
    }
}
