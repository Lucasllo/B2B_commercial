package com.orderflow.order.saga.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.orderflow.order.saga.messaging.dto.ReservationFailureLine;
import com.orderflow.order.saga.messaging.dto.StockReservationFailedEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Valida e converte o corpo cru da {@code order-events-queue} num evento de resultado da saga
 * (D-67, ASVS V5) — mesma disciplina de {@code SagaCommandParser} do inventory-service: teto de
 * tamanho antes do parse, {@code FAIL_ON_TRAILING_TOKENS}, raiz objeto, todas as regras de campo do
 * contrato, valores externos sempre sanitizados antes de entrar numa mensagem de exceção/log.
 *
 * <p>Despacha por {@code eventType} — nesta task (05-03 Task 1) só {@code StockReservationFailed}
 * é reconhecido; {@code StockReserved} entra na Task 2, quando o retorno passa a poder ser um dos
 * dois tipos (o chamador distingue por {@code instanceof}, sem precisar de uma interface própria).
 */
@Component
public class SagaEventParser {

    private static final int MAX_RAW_PAYLOAD_BYTES = 64 * 1024;
    private static final int SANITIZED_VALUE_MAX_LENGTH = 64;

    private final ObjectReader eventReader;

    public SagaEventParser(ObjectMapper objectMapper) {
        // FAIL_ON_TRAILING_TOKENS detecta conteudo depois do valor JSON valido — mesma tecnica de
        // SagaCommandParser/NotificationService.
        this.eventReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public Object parse(String rawPayload) {
        if (rawPayload == null
                || rawPayload.getBytes(StandardCharsets.UTF_8).length > MAX_RAW_PAYLOAD_BYTES) {
            throw new InvalidSagaMessageException(
                    "Corpo da mensagem excede o tamanho maximo permitido de " + MAX_RAW_PAYLOAD_BYTES + " bytes");
        }

        JsonNode tree;
        try {
            tree = eventReader.readTree(rawPayload);
        } catch (JsonProcessingException e) {
            throw new InvalidSagaMessageException("Corpo da mensagem nao e um JSON valido");
        }

        if (tree == null || !tree.isObject()) {
            throw new InvalidSagaMessageException("Corpo da mensagem nao e um objeto JSON");
        }

        String eventType = optionalText(tree, "eventType");
        if (StockReservationFailedEvent.EVENT_TYPE.equals(eventType)) {
            return parseReservationFailed(tree, eventType);
        }
        throw new InvalidSagaMessageException("Tipo de evento nao suportado: " + sanitizeForLog(eventType));
    }

    private StockReservationFailedEvent parseReservationFailed(JsonNode tree, String eventType) {
        UUID eventId = requireUuid(tree, "eventId");
        Instant occurredAt = requireInstant(tree, "occurredAt");
        UUID orderId = requireUuid(tree, "orderId");
        String reservationId = requireReservationIdMatchingOrderId(tree, orderId);
        String reasonCode = requireReasonCode(tree);
        List<ReservationFailureLine> failures = requireFailures(tree);
        return new StockReservationFailedEvent(eventId, eventType, occurredAt, orderId, reservationId, reasonCode, failures);
    }

    private String requireReservationIdMatchingOrderId(JsonNode tree, UUID orderId) {
        String reservationId = requireText(tree, "reservationId");
        if (!reservationId.equals(orderId.toString())) {
            throw new InvalidSagaMessageException(
                    "Campo reservationId diferente de orderId: " + sanitizeForLog(reservationId));
        }
        return reservationId;
    }

    private String requireReasonCode(JsonNode tree) {
        String reasonCode = requireText(tree, "reasonCode");
        if (!(StockReservationFailedEvent.INSUFFICIENT_STOCK.equals(reasonCode)
                || StockReservationFailedEvent.PRODUCT_NOT_STOCKED.equals(reasonCode)
                || StockReservationFailedEvent.RESERVATION_CANCELLED.equals(reasonCode))) {
            throw new InvalidSagaMessageException(
                    "Campo reasonCode fora dos valores permitidos: " + sanitizeForLog(reasonCode));
        }
        return reasonCode;
    }

    /** {@code failures} pode ser uma lista vazia ({@code RESERVATION_CANCELLED}) — nunca ausente. */
    private List<ReservationFailureLine> requireFailures(JsonNode root) {
        JsonNode failuresNode = root.get("failures");
        if (failuresNode == null || failuresNode.isNull() || !failuresNode.isArray()) {
            throw new InvalidSagaMessageException("Campo failures ausente ou nao e uma lista");
        }
        List<ReservationFailureLine> failures = new ArrayList<>(failuresNode.size());
        for (JsonNode failureNode : failuresNode) {
            UUID productId = requireUuid(failureNode, "productId");
            int requested = requireIntAtLeast(failureNode, "requested", 1);
            int available = requireIntAtLeast(failureNode, "available", 0);
            failures.add(new ReservationFailureLine(productId, requested, available));
        }
        return failures;
    }

    private int requireIntAtLeast(JsonNode node, String field, int min) {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull() || !valueNode.isIntegralNumber()) {
            throw new InvalidSagaMessageException("Campo " + field + " ausente ou nao e um numero inteiro");
        }
        long value = valueNode.asLong();
        if (value < min) {
            throw new InvalidSagaMessageException(
                    "Campo " + field + " fora da faixa permitida (minimo " + min + "): " + value);
        }
        return (int) value;
    }

    private String requireText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new InvalidSagaMessageException("Campo " + field + " ausente");
        }
        return value;
    }

    private UUID requireUuid(JsonNode node, String field) {
        String value = requireText(node, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidSagaMessageException(
                    "Campo " + field + " nao e um UUID valido: " + sanitizeForLog(value));
        }
    }

    private Instant requireInstant(JsonNode node, String field) {
        String value = requireText(node, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidSagaMessageException(
                    "Campo " + field + " nao e um instante ISO-8601 valido: " + sanitizeForLog(value));
        }
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    /**
     * O valor recebido vem de fora e acabaria numa mensagem de excecao/log WARN do listener —
     * mesma tecnica de {@code SagaCommandParser}/{@code NotificationService.sanitizeForLog}.
     */
    private static String sanitizeForLog(String value) {
        if (value == null) {
            return "(ausente)";
        }
        String sanitized = value.replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]", "_");
        return sanitized.length() > SANITIZED_VALUE_MAX_LENGTH
                ? sanitized.substring(0, SANITIZED_VALUE_MAX_LENGTH)
                : sanitized;
    }
}
