package com.orderflow.notification.history.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.orderflow.notification.history.NotificationRecord;

import java.time.Instant;

/**
 * Corpo de resposta de {@code GET /notifications/{productId}} e de
 * {@code GET /notifications/orders/{orderId}}; {@code entityId} e o id do produto ou do pedido
 * (D-80). O {@code payload} sai como objeto
 * JSON aninhado, nao como texto escapado, para que quem abrir o endpoint pela Swagger UI leia o
 * evento original sem decodificar nada.
 */
public record NotificationResponse(
        String entityId,
        String eventId,
        String eventType,
        String message,
        JsonNode payload,
        Instant occurredAt,
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
