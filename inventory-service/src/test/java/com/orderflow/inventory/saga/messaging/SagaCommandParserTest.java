package com.orderflow.inventory.saga.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.saga.messaging.dto.ReleaseStockCommand;
import com.orderflow.inventory.saga.messaging.dto.ReserveStockCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unitário puro (sem contexto Spring) — {@code SagaCommandParser} injeta só um {@code ObjectMapper},
 * então um {@code new ObjectMapper()} simples basta; nenhum campo desta classe é serializado via
 * Jackson databind (datas são convertidas manualmente por {@code Instant.parse}), então não é
 * preciso registrar o módulo de tempo do Jackson.
 */
class SagaCommandParserTest {

    private final SagaCommandParser parser = new SagaCommandParser(new ObjectMapper());

    private String validCommand(UUID eventId, UUID orderId, String reservationId, UUID productId, int quantity) {
        return """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":[{"productId":"%s","quantity":%d}]}
                """.formatted(eventId, orderId, reservationId, productId, quantity);
    }

    private String validCommand(UUID orderId) {
        return validCommand(UUID.randomUUID(), orderId, orderId.toString(), UUID.randomUUID(), 3);
    }

    @Test
    void validReserveStockParsesIntoCommand() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String body = validCommand(eventId, orderId, orderId.toString(), productId, 3);

        ReserveStockCommand command = (ReserveStockCommand) parser.parse(body);

        assertThat(command.eventId()).isEqualTo(eventId);
        assertThat(command.eventType()).isEqualTo("ReserveStock");
        assertThat(command.occurredAt()).isEqualTo(Instant.parse("2026-09-26T12:00:00Z"));
        assertThat(command.orderId()).isEqualTo(orderId);
        assertThat(command.reservationId()).isEqualTo(orderId.toString());
        assertThat(command.items()).hasSize(1);
        assertThat(command.items().get(0).productId()).isEqualTo(productId);
        assertThat(command.items().get(0).quantity()).isEqualTo(3);
    }

    @Test
    void bodyOver64KbIsRejected() {
        String hugePadding = "x".repeat(70 * 1024);
        assertThatThrownBy(() -> parser.parse(hugePadding))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("tamanho maximo");
    }

    @Test
    void nullBodyIsRejected() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(InvalidSagaMessageException.class);
    }

    @Test
    void invalidJsonIsRejected() {
        assertThatThrownBy(() -> parser.parse("isto nao e json"))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("JSON valido");
    }

    @Test
    void contentAfterJsonObjectIsRejected() {
        String body = validCommand(UUID.randomUUID()).strip() + " {\"extra\":true}";
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class);
    }

    @Test
    void nonObjectRootIsRejected() {
        assertThatThrownBy(() -> parser.parse("[1,2,3]"))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("objeto JSON");
    }

    @Test
    void unknownEventTypeIsRejected() {
        String body = """
                {"eventId":"%s","eventType":"Foo","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":[{"productId":"%s","quantity":1}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("Tipo de evento nao suportado");
    }

    @Test
    void missingEventTypeIsRejected() {
        String body = """
                {"eventId":"%s","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":[{"productId":"%s","quantity":1}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body)).isInstanceOf(InvalidSagaMessageException.class);
    }

    @Test
    void missingEventIdIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":[{"productId":"%s","quantity":1}]}
                """.formatted(orderId, orderId, UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void missingOccurredAtIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReserveStock",\
                "orderId":"%s","reservationId":"%s","items":[{"productId":"%s","quantity":1}]}
                """.formatted(UUID.randomUUID(), orderId, orderId, UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    void missingOrderIdIsRejected() {
        String body = """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "reservationId":"whatever","items":[{"productId":"%s","quantity":1}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("orderId");
    }

    @Test
    void missingReservationIdIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","items":[{"productId":"%s","quantity":1}]}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("reservationId");
    }

    @Test
    void missingItemsIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s"}
                """.formatted(UUID.randomUUID(), orderId, orderId);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("items");
    }

    @Test
    void reservationIdDifferentFromOrderIdIsRejected() {
        String body = validCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(),
                UUID.randomUUID(), 1);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("reservationId");
    }

    @Test
    void emptyItemsIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":[]}
                """.formatted(UUID.randomUUID(), orderId, orderId);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("items");
    }

    @Test
    void moreThanFiftyItemsIsRejected() {
        UUID orderId = UUID.randomUUID();
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                items.append(",");
            }
            items.append("{\"productId\":\"").append(UUID.randomUUID()).append("\",\"quantity\":1}");
        }
        items.append("]");
        String body = """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":%s}
                """.formatted(UUID.randomUUID(), orderId, orderId, items);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("items");
    }

    @Test
    void nullProductIdIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":[{"productId":null,"quantity":1}]}
                """.formatted(UUID.randomUUID(), orderId, orderId);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("productId");
    }

    @Test
    void duplicatedProductIdIsRejected() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s",\
                "items":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":2}]}
                """.formatted(UUID.randomUUID(), orderId, orderId, productId, productId);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("repetido");
    }

    @Test
    void quantityBelowOneIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":[{"productId":"%s","quantity":0}]}
                """.formatted(UUID.randomUUID(), orderId, orderId, UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void quantityAboveMaxIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":[{"productId":"%s","quantity":1000001}]}
                """.formatted(UUID.randomUUID(), orderId, orderId, UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void quantityAtMaxIsAccepted() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":[{"productId":"%s","quantity":1000000}]}
                """.formatted(UUID.randomUUID(), orderId, orderId, UUID.randomUUID());

        ReserveStockCommand command = (ReserveStockCommand) parser.parse(body);

        assertThat(command.items().get(0).quantity()).isEqualTo(1_000_000);
    }

    @Test
    void exceptionMessageIsSanitizedAndBoundedInLength() {
        String longSuffix = "Y".repeat(100);
        // \\u0007 dentro do texto Java vira o escape JSON valido \u0007 no corpo enviado ao parser
        // — decodificado pelo Jackson como o caractere de controle BEL de verdade, nunca um byte
        // de controle literal no source (que o Jackson rejeitaria como JSON invalido).
        String body = """
                {"eventId":"%s","eventType":"Foo\\u0007Bar%s","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":[{"productId":"%s","quantity":1}]}
                """.formatted(UUID.randomUUID(), longSuffix, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        InvalidSagaMessageException ex = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidSagaMessageException.class, () -> parser.parse(body));

        assertThat(ex.getMessage()).doesNotContainPattern("\\p{Cntrl}");
        assertThat(ex.getMessage().length()).isLessThan(150);
    }

    // -----------------------------------------------------------------------------------------
    // ReleaseStock (05-04 Task 2, D-63/D-66) — mesmo envelope do ReserveStock, campo reason a mais.
    // -----------------------------------------------------------------------------------------

    private String validReleaseCommand(UUID eventId, UUID orderId, String reason, UUID productId, int quantity) {
        return """
                {"eventId":"%s","eventType":"ReleaseStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","reason":"%s",\
                "items":[{"productId":"%s","quantity":%d}]}
                """.formatted(eventId, orderId, orderId, reason, productId, quantity);
    }

    @Test
    void validReleaseStockWithTimeoutReasonParsesIntoCommand() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String body = validReleaseCommand(eventId, orderId, "RESERVATION_TIMEOUT", productId, 2);

        ReleaseStockCommand command = (ReleaseStockCommand) parser.parse(body);

        assertThat(command.eventId()).isEqualTo(eventId);
        assertThat(command.eventType()).isEqualTo("ReleaseStock");
        assertThat(command.orderId()).isEqualTo(orderId);
        assertThat(command.reservationId()).isEqualTo(orderId.toString());
        assertThat(command.reason()).isEqualTo("RESERVATION_TIMEOUT");
        assertThat(command.items()).hasSize(1);
        assertThat(command.items().get(0).productId()).isEqualTo(productId);
        assertThat(command.items().get(0).quantity()).isEqualTo(2);
    }

    @Test
    void validReleaseStockWithLateReservationReasonParsesIntoCommand() {
        UUID orderId = UUID.randomUUID();
        String body = validReleaseCommand(UUID.randomUUID(), orderId, "LATE_RESERVATION", UUID.randomUUID(), 1);

        ReleaseStockCommand command = (ReleaseStockCommand) parser.parse(body);

        assertThat(command.reason()).isEqualTo("LATE_RESERVATION");
    }

    @Test
    void releaseStockWithMissingReasonIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReleaseStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","items":[{"productId":"%s","quantity":1}]}
                """.formatted(UUID.randomUUID(), orderId, orderId, UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void releaseStockWithUnknownReasonIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = validReleaseCommand(UUID.randomUUID(), orderId, "SOMETHING_ELSE", UUID.randomUUID(), 1);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void releaseStockReservationIdDifferentFromOrderIdIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReleaseStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","reason":"RESERVATION_TIMEOUT",\
                "items":[{"productId":"%s","quantity":1}]}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("reservationId");
    }

    @Test
    void releaseStockWithEmptyItemsIsRejectedSameRuleAsReserveStock() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"ReleaseStock","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","reason":"RESERVATION_TIMEOUT","items":[]}
                """.formatted(UUID.randomUUID(), orderId, orderId);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("items");
    }
}
