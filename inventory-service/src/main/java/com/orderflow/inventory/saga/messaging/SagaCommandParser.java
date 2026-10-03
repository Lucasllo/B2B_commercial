package com.orderflow.inventory.saga.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.orderflow.inventory.saga.messaging.dto.ReleaseStockCommand;
import com.orderflow.inventory.saga.messaging.dto.ReservationLine;
import com.orderflow.inventory.saga.messaging.dto.ReserveStockCommand;
import com.orderflow.inventory.saga.messaging.dto.ShipStockCommand;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Valida e converte o corpo cru da {@code inventory-commands-queue} num {@link ReserveStockCommand}
 * (D-67, ASVS V5) — mesma disciplina de {@code NotificationService.record} (Fase 3): teto de
 * tamanho antes do parse, {@code FAIL_ON_TRAILING_TOKENS}, raiz objeto, e todas as regras de campo
 * do contrato. Nenhum valor recebido de fora aparece cru numa mensagem de exceção — sempre
 * sanitizado por {@link #sanitizeForLog(String)}.
 *
 * <p>Despacha por {@code eventType}: {@code ReserveStock} vira {@link ReserveStockCommand}, {@code
 * ReleaseStock} (D-63, D-66, 05-04) vira {@link ReleaseStockCommand} — o chamador ({@link
 * ReservationCommandListener}) distingue por {@code instanceof}, mesma técnica do {@code
 * SagaEventParser} do order-service (05-03). Um {@code eventType} desconhecido (incluindo ausente)
 * é rejeitado como mensagem inválida.
 */
@Component
public class SagaCommandParser {

    private static final int MAX_RAW_PAYLOAD_BYTES = 64 * 1024;
    private static final int SANITIZED_VALUE_MAX_LENGTH = 64;
    private static final int MAX_ITEMS_PER_ORDER = 50;
    private static final long MAX_QUANTITY_PER_ITEM = 1_000_000L;

    private final ObjectReader eventReader;

    public SagaCommandParser(ObjectMapper objectMapper) {
        // FAIL_ON_TRAILING_TOKENS detecta conteudo depois do valor JSON valido — uma mensagem
        // venenosa nao pode passar por valida so porque o primeiro objeto do corpo e bem formado
        // (mesma tecnica de NotificationService).
        this.eventReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * @return {@link ReserveStockCommand}, {@link ReleaseStockCommand} ou {@link ShipStockCommand},
     *     conforme {@code eventType} — o chamador distingue por {@code instanceof}.
     */
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
        if (ReserveStockCommand.EVENT_TYPE.equals(eventType)) {
            return parseReserveStock(tree, eventType);
        }
        if (ReleaseStockCommand.EVENT_TYPE.equals(eventType)) {
            return parseReleaseStock(tree, eventType);
        }
        if (ShipStockCommand.EVENT_TYPE.equals(eventType)) {
            try {
                return parseShipStock(tree, eventType);
            } catch (InvalidSagaMessageException e) {
                // D-107/WR-01: ShipStock so existe para pedido ja SHIPPED — invalido e anomalia
                // tecnica (DLQ), nao descarte. orderId lido de forma tolerante e sanitizado.
                String orderIdForLog = optionalText(tree, "orderId");
                throw new InvalidShipStockException(e.getMessage(),
                        orderIdForLog == null ? "?" : sanitizeForLog(orderIdForLog));
            }
        }
        throw new InvalidSagaMessageException("Tipo de evento nao suportado: " + sanitizeForLog(eventType));
    }

    /**
     * {@code ShipStock} (06-03, D-75): mesma validacao de {@code ReserveStock}, sem {@code reason}.
     * {@code items} so identifica os produtos — a quantidade baixada vem do livro, mas a faixa
     * 1..1000000 e a regra de itens (sem repetido, 1..50) continuam valendo para o corpo recebido.
     */
    private ShipStockCommand parseShipStock(JsonNode tree, String eventType) {
        UUID eventId = requireUuid(tree, "eventId");
        Instant occurredAt = requireInstant(tree, "occurredAt");
        UUID orderId = requireUuid(tree, "orderId");
        String reservationId = requireReservationIdMatchingOrderId(tree, orderId);
        List<ReservationLine> items = requireItems(tree);

        return new ShipStockCommand(eventId, eventType, occurredAt, orderId, reservationId, items);
    }

    private ReserveStockCommand parseReserveStock(JsonNode tree, String eventType) {
        UUID eventId = requireUuid(tree, "eventId");
        Instant occurredAt = requireInstant(tree, "occurredAt");
        UUID orderId = requireUuid(tree, "orderId");
        String reservationId = requireReservationIdMatchingOrderId(tree, orderId);
        List<ReservationLine> items = requireItems(tree);

        return new ReserveStockCommand(eventId, eventType, occurredAt, orderId, reservationId, items);
    }

    /** {@code reason} restrito aos dois valores emitidos pelo order-service (05-04, D-63). */
    private ReleaseStockCommand parseReleaseStock(JsonNode tree, String eventType) {
        UUID eventId = requireUuid(tree, "eventId");
        Instant occurredAt = requireInstant(tree, "occurredAt");
        UUID orderId = requireUuid(tree, "orderId");
        String reservationId = requireReservationIdMatchingOrderId(tree, orderId);
        String reason = requireText(tree, "reason");
        if (!ReleaseStockCommand.RESERVATION_TIMEOUT.equals(reason)
                && !ReleaseStockCommand.LATE_RESERVATION.equals(reason)) {
            throw new InvalidSagaMessageException("Campo reason desconhecido: " + sanitizeForLog(reason));
        }
        List<ReservationLine> items = requireItems(tree);

        return new ReleaseStockCommand(eventId, eventType, occurredAt, orderId, reservationId, reason, items);
    }

    private String requireReservationIdMatchingOrderId(JsonNode tree, UUID orderId) {
        String reservationId = requireText(tree, "reservationId");
        if (!reservationId.equals(orderId.toString())) {
            throw new InvalidSagaMessageException(
                    "Campo reservationId diferente de orderId: " + sanitizeForLog(reservationId));
        }
        return reservationId;
    }

    private List<ReservationLine> requireItems(JsonNode root) {
        JsonNode itemsNode = root.get("items");
        if (itemsNode == null || itemsNode.isNull() || !itemsNode.isArray()) {
            throw new InvalidSagaMessageException("Campo items ausente ou nao e uma lista");
        }
        if (itemsNode.isEmpty()) {
            throw new InvalidSagaMessageException("Campo items nao pode ser vazio");
        }
        if (itemsNode.size() > MAX_ITEMS_PER_ORDER) {
            throw new InvalidSagaMessageException(
                    "Campo items excede o maximo de " + MAX_ITEMS_PER_ORDER + " itens");
        }

        List<ReservationLine> items = new ArrayList<>(itemsNode.size());
        Set<UUID> seenProductIds = new HashSet<>();
        for (JsonNode itemNode : itemsNode) {
            UUID productId = requireUuid(itemNode, "productId");
            if (!seenProductIds.add(productId)) {
                throw new InvalidSagaMessageException(
                        "Campo productId repetido em items: " + sanitizeForLog(productId.toString()));
            }
            long quantity = requireQuantity(itemNode);
            items.add(new ReservationLine(productId, (int) quantity));
        }
        return items;
    }

    private long requireQuantity(JsonNode itemNode) {
        JsonNode quantityNode = itemNode.get("quantity");
        if (quantityNode == null || quantityNode.isNull() || !quantityNode.isIntegralNumber()) {
            throw new InvalidSagaMessageException("Campo quantity ausente ou nao e um numero inteiro");
        }
        long quantity = quantityNode.asLong();
        if (quantity < 1 || quantity > MAX_QUANTITY_PER_ITEM) {
            throw new InvalidSagaMessageException(
                    "Campo quantity fora da faixa permitida (1.." + MAX_QUANTITY_PER_ITEM + "): " + quantity);
        }
        return quantity;
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
     * O valor recebido vem de fora e acabaria numa mensagem de excecao/log WARN do listener — troca
     * qualquer caractere de controle (inclusive NEL, U+0085) e os separadores de linha/paragrafo
     * Unicode U+2028 e U+2029 por {@code _} e corta em 64 caracteres, mesma tecnica de
     * {@code NotificationService.sanitizeForLog} (Fase 3) — impede injecao de linha de log e
     * mensagens de excecao desproporcionalmente grandes.
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
