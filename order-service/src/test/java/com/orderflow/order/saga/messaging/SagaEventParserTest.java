package com.orderflow.order.saga.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.saga.messaging.dto.StockReservationFailedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unitário puro (sem contexto Spring) — mesmo estilo de {@code SagaCommandParserTest} do
 * inventory-service. Task 1 (05-03): só {@code StockReservationFailed}; {@code StockReserved}
 * entra em {@code saga.messaging.SagaEventParserTest} novamente na Task 2 (mesmo arquivo,
 * ampliado).
 */
class SagaEventParserTest {

    private final SagaEventParser parser = new SagaEventParser(new ObjectMapper());

    private String validFailedBody(UUID eventId, UUID orderId, String reservationId, String reasonCode,
                                    String failuresJson) {
        return """
                {"eventId":"%s","eventType":"StockReservationFailed","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","reasonCode":"%s","failures":%s}
                """.formatted(eventId, orderId, reservationId, reasonCode, failuresJson);
    }

    private String failureLine(UUID productId, int requested, int available) {
        return "{\"productId\":\"" + productId + "\",\"requested\":" + requested + ",\"available\":" + available + "}";
    }

    @Test
    void validStockReservationFailedParsesIntoEvent() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String body = validFailedBody(eventId, orderId, orderId.toString(), "INSUFFICIENT_STOCK",
                "[" + failureLine(productId, 5, 2) + "]");

        Object parsed = parser.parse(body);

        assertThat(parsed).isInstanceOf(StockReservationFailedEvent.class);
        StockReservationFailedEvent event = (StockReservationFailedEvent) parsed;
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.eventType()).isEqualTo("StockReservationFailed");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-26T12:00:00Z"));
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.reservationId()).isEqualTo(orderId.toString());
        assertThat(event.reasonCode()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(event.failures()).hasSize(1);
        assertThat(event.failures().get(0).productId()).isEqualTo(productId);
        assertThat(event.failures().get(0).requested()).isEqualTo(5);
        assertThat(event.failures().get(0).available()).isEqualTo(2);
    }

    @Test
    void reservationCancelledWithEmptyFailuresParses() {
        UUID orderId = UUID.randomUUID();
        String body = validFailedBody(UUID.randomUUID(), orderId, orderId.toString(), "RESERVATION_CANCELLED", "[]");

        Object parsed = parser.parse(body);

        StockReservationFailedEvent event = (StockReservationFailedEvent) parsed;
        assertThat(event.reasonCode()).isEqualTo("RESERVATION_CANCELLED");
        assertThat(event.failures()).isEmpty();
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
        UUID orderId = UUID.randomUUID();
        String body = validFailedBody(UUID.randomUUID(), orderId, orderId.toString(), "INSUFFICIENT_STOCK", "[]")
                .strip() + " {\"extra\":true}";
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
                "orderId":"%s","reservationId":"%s","reasonCode":"INSUFFICIENT_STOCK","failures":[]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("Tipo de evento nao suportado");
    }

    @Test
    void missingEventIdIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventType":"StockReservationFailed","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","reasonCode":"INSUFFICIENT_STOCK","failures":[]}
                """.formatted(orderId, orderId);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void missingOccurredAtIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"StockReservationFailed",\
                "orderId":"%s","reservationId":"%s","reasonCode":"INSUFFICIENT_STOCK","failures":[]}
                """.formatted(UUID.randomUUID(), orderId, orderId);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    void missingOrderIdIsRejected() {
        String body = """
                {"eventId":"%s","eventType":"StockReservationFailed","occurredAt":"2026-09-26T12:00:00Z",\
                "reservationId":"whatever","reasonCode":"INSUFFICIENT_STOCK","failures":[]}
                """.formatted(UUID.randomUUID());
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("orderId");
    }

    @Test
    void missingReasonCodeIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"StockReservationFailed","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","failures":[]}
                """.formatted(UUID.randomUUID(), orderId, orderId);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("reasonCode");
    }

    @Test
    void reasonCodeOutsideAllowedValuesIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = validFailedBody(UUID.randomUUID(), orderId, orderId.toString(), "SOMETHING_ELSE", "[]");
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("reasonCode");
    }

    @Test
    void reservationIdDifferentFromOrderIdIsRejected() {
        String body = validFailedBody(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(),
                "INSUFFICIENT_STOCK", "[]");
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("reservationId");
    }

    @Test
    void missingFailuresIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"StockReservationFailed","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","reasonCode":"INSUFFICIENT_STOCK"}
                """.formatted(UUID.randomUUID(), orderId, orderId);
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("failures");
    }

    @Test
    void failureWithNullProductIdIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = validFailedBody(UUID.randomUUID(), orderId, orderId.toString(), "INSUFFICIENT_STOCK",
                "[{\"productId\":null,\"requested\":1,\"available\":0}]");
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("productId");
    }

    @Test
    void failureWithRequestedBelowOneIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = validFailedBody(UUID.randomUUID(), orderId, orderId.toString(), "INSUFFICIENT_STOCK",
                "[" + failureLine(UUID.randomUUID(), 0, 0) + "]");
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("requested");
    }

    @Test
    void failureWithNegativeAvailableIsRejected() {
        UUID orderId = UUID.randomUUID();
        String body = validFailedBody(UUID.randomUUID(), orderId, orderId.toString(), "INSUFFICIENT_STOCK",
                "[" + failureLine(UUID.randomUUID(), 1, -1) + "]");
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(InvalidSagaMessageException.class)
                .hasMessageContaining("available");
    }

    @Test
    void exceptionMessageIsSanitizedAndBoundedInLength() {
        String longSuffix = "Y".repeat(100);
        String body = """
                {"eventId":"%s","eventType":"Foo\\u0007Bar%s","occurredAt":"2026-09-26T12:00:00Z",\
                "orderId":"%s","reservationId":"%s","reasonCode":"INSUFFICIENT_STOCK","failures":[]}
                """.formatted(UUID.randomUUID(), longSuffix, UUID.randomUUID(), UUID.randomUUID());

        InvalidSagaMessageException ex = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidSagaMessageException.class, () -> parser.parse(body));

        assertThat(ex.getMessage()).doesNotContainPattern("\\p{Cntrl}");
        assertThat(ex.getMessage().length()).isLessThan(150);
    }
}
