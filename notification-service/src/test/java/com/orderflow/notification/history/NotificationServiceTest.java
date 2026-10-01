package com.orderflow.notification.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.notification.history.dto.NotificationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 2: unitario, JUnit + Mockito, sem container — NotificationRepository mockado e um
 * ObjectMapper real construido por Jackson2ObjectMapperBuilder.json().build().
 */
class NotificationServiceTest {

    private NotificationRepository notificationRepository;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        notificationService = new NotificationService(notificationRepository, objectMapper);
    }

    private String validEventBody(UUID eventId, UUID productId, int previous, int next) {
        return """
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":%d,"newQuantityOnHand":%d,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(eventId, productId, previous, next);
    }

    @Test
    void validEventProducesRecordWithDeterministicKeyAndPopulatedFields() {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        notificationService.record(validEventBody(eventId, productId, 5, 12));

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRepository).save(captor.capture());
        NotificationRecord record = captor.getValue();

        assertThat(record.getEntityId()).isEqualTo(productId.toString());
        assertThat(record.getCompanyId()).isNull();
        assertThat(record.getSortKey()).isEqualTo("STOCK_ADJUSTED#" + eventId);
        assertThat(record.getEventId()).isEqualTo(eventId.toString());
        assertThat(record.getEventType()).isEqualTo("STOCK_ADJUSTED");
        assertThat(record.getOccurredAt()).isEqualTo(Instant.parse("2026-09-22T12:00:00Z"));
        assertThat(record.getRecordedAt()).isNotNull();
    }

    @Test
    void readableMessageUsesPluralUnitsAndSingularForOne() {
        UUID productId = UUID.randomUUID();

        notificationService.record(validEventBody(UUID.randomUUID(), productId, 5, 12));
        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getMessage())
                .isEqualTo("Estoque do produto " + productId + " ajustado de 5 para 12 unidades");

        NotificationRepository singularRepo = mock(NotificationRepository.class);
        NotificationService singularService = new NotificationService(
                singularRepo, Jackson2ObjectMapperBuilder.json().build());
        singularService.record(validEventBody(UUID.randomUUID(), productId, 0, 1));
        ArgumentCaptor<NotificationRecord> singularCaptor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(singularRepo).save(singularCaptor.capture());
        assertThat(singularCaptor.getValue().getMessage()).endsWith("para 1 unidade");
    }

    @Test
    void unknownExtraFieldIsAcceptedAndPreservedInRawPayload() {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z","warehouseId":"WH-1"}
                """.formatted(eventId, productId);

        notificationService.record(body);

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getRawPayload()).contains("warehouseId").contains("WH-1");
    }

    private static Stream<String> invalidEventBodies() {
        UUID validEventId = UUID.randomUUID();
        UUID validProductId = UUID.randomUUID();
        return Stream.of(
                "isto nao e json",
                "[1,2,3]",
                """
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"} lixo depois
                """.formatted(validEventId, validProductId),
                """
                {"eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(validProductId),
                """
                {"eventId":"nao-e-um-uuid","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(validProductId),
                """
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(validEventId),
                """
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12}
                """.formatted(validEventId, validProductId),
                """
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":5,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(validEventId, validProductId),
                """
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":-1,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(validEventId, validProductId),
                """
                {"eventId":"%s","eventType":"ORDER_TELEPORTED","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(validEventId, validProductId),
                """
                {"eventId":"%s","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(validEventId, validProductId)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidEventBodies")
    void invalidBodiesThrowAndNeverCallRepository(String body) {
        assertThatThrownBy(() -> notificationService.record(body))
                .isInstanceOf(InvalidNotificationEventException.class);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void unsupportedEventTypeWithNewlineProducesSanitizedMessage() {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String maliciousType = "EVIL\nTYPE\r\nWITH\tCONTROL" + "X".repeat(200);
        String body = """
                {"eventId":"%s","eventType":"%s","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(eventId, maliciousType, productId);

        assertThatThrownBy(() -> notificationService.record(body))
                .isInstanceOf(InvalidNotificationEventException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("\n").doesNotContain("\r");
                    assertThat(e.getMessage().length()).isLessThanOrEqualTo(200);
                });
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void unsupportedEventTypeWithAnsiEscapeAndUnicodeLineSeparatorProducesSanitizedMessage() {
        // WR-06: o regex antigo cobria so tres caracteres de controle. ESC (codepoint 27, usado
        // em sequencias ANSI de terminal) e o separador de linha Unicode (codepoint 8232, que
        // varios agregadores de log tratam como quebra) tinham que passar intactos antes da
        // correcao. Construidos via (char) para nao depender de escapes no fonte.
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String esc = String.valueOf((char) 27);
        String unicodeLineSeparator = String.valueOf((char) 8232);
        String maliciousType = "EVIL" + esc + "TYPE" + unicodeLineSeparator + "CONTROL" + "X".repeat(190);
        String body = """
                {"eventId":"%s","eventType":"%s","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(eventId, maliciousType, productId);

        assertThatThrownBy(() -> notificationService.record(body))
                .isInstanceOf(InvalidNotificationEventException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(esc).doesNotContain(unicodeLineSeparator);
                    assertThat(e.getMessage().length()).isLessThanOrEqualTo(200);
                });
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void repositoryRuntimeExceptionOnSavePropagatesUnconverted() {
        when(notificationRepository.findByEntityId(any())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("dynamo down"))
                .when(notificationRepository).save(any());

        assertThatThrownBy(() -> notificationService.record(
                validEventBody(UUID.randomUUID(), UUID.randomUUID(), 5, 12)))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(InvalidNotificationEventException.class)
                .hasMessage("dynamo down");
    }

    @Test
    void historyReturnsRecordsOrderedByOccurredAtThenSortKeyRegardlessOfRepositoryOrder() {
        UUID productId = UUID.randomUUID();
        NotificationRecord later = rawRecord(productId, "STOCK_ADJUSTED#zzz", Instant.parse("2026-09-22T12:05:00Z"));
        NotificationRecord earlierA = rawRecord(productId, "STOCK_ADJUSTED#aaa", Instant.parse("2026-09-22T12:00:00Z"));
        NotificationRecord earlierB = rawRecord(productId, "STOCK_ADJUSTED#bbb", Instant.parse("2026-09-22T12:00:00Z"));
        when(notificationRepository.findByEntityId(productId.toString()))
                .thenReturn(List.of(later, earlierB, earlierA));

        List<NotificationResponse> history = notificationService.history(productId);

        assertThat(history).extracting(NotificationResponse::eventId)
                .containsExactly(
                        earlierA.getEventId(),
                        earlierB.getEventId(),
                        later.getEventId());
    }

    // ---- Plano 06-04 Task 1: ORDER_CREATED na particao generica entityId (D-80, D-82) ----

    private static String orderCreatedBody(UUID eventId, UUID orderId, UUID companyId, String total) {
        return """
                {"eventId":"%s","eventType":"ORDER_CREATED","occurredAt":"2026-09-30T12:00:00.123456Z","orderId":"%s","companyId":"%s","createdBy":"buyer-1","total":%s}
                """.formatted(eventId, orderId, companyId, total);
    }

    @Test
    void orderCreatedProducesRecordPartitionedByOrderIdWithCompanyAndMessage() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        notificationService.record(orderCreatedBody(eventId, orderId, companyId, "40.00"));

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRepository).save(captor.capture());
        NotificationRecord record = captor.getValue();
        assertThat(record.getEntityId()).isEqualTo(orderId.toString());
        assertThat(record.getCompanyId()).isEqualTo(companyId.toString());
        assertThat(record.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(record.getEventId()).isEqualTo(eventId.toString());
        assertThat(record.getSortKey()).isEqualTo("ORDER_CREATED#" + eventId);
        assertThat(record.getRawPayload()).contains("createdBy").contains(orderId.toString());
        assertThat(record.getMessage()).isEqualTo("Pedido criado — total 40.00");
        assertThat(record.getOccurredAt()).isEqualTo(Instant.parse("2026-09-30T12:00:00.123456Z"));
    }

    private static Stream<String> invalidOrderCreatedBodies() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        return Stream.of(
                // sem companyId
                """
                {"eventId":"%s","eventType":"ORDER_CREATED","occurredAt":"2026-09-30T12:00:00Z","orderId":"%s","createdBy":"buyer-1","total":40.00}
                """.formatted(eventId, orderId),
                // orderId que nao e UUID
                """
                {"eventId":"%s","eventType":"ORDER_CREATED","occurredAt":"2026-09-30T12:00:00Z","orderId":"nao-e-uuid","companyId":"%s","createdBy":"buyer-1","total":40.00}
                """.formatted(eventId, companyId),
                // sem total
                """
                {"eventId":"%s","eventType":"ORDER_CREATED","occurredAt":"2026-09-30T12:00:00Z","orderId":"%s","companyId":"%s","createdBy":"buyer-1"}
                """.formatted(eventId, orderId, companyId)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidOrderCreatedBodies")
    void invalidOrderCreatedThrowsAndNeverCallsRepository(String body) {
        assertThatThrownBy(() -> notificationService.record(body))
                .isInstanceOf(InvalidNotificationEventException.class);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void historyForOrderReturnsOnlyOrderEventTypes() {
        UUID orderId = UUID.randomUUID();
        NotificationRecord created = rawRecord(orderId, "ORDER_CREATED#aaa", Instant.parse("2026-09-30T12:00:00Z"));
        created.setEventType("ORDER_CREATED");
        NotificationRecord stock = rawRecord(orderId, "STOCK_ADJUSTED#bbb", Instant.parse("2026-09-30T12:01:00Z"));
        when(notificationRepository.findByEntityId(orderId.toString())).thenReturn(List.of(stock, created));

        List<NotificationResponse> timeline = notificationService.historyForOrder(orderId);

        assertThat(timeline).extracting(NotificationResponse::eventType).containsExactly("ORDER_CREATED");
    }

    private NotificationRecord rawRecord(UUID productId, String sortKey, Instant occurredAt) {
        NotificationRecord record = new NotificationRecord();
        record.setEntityId(productId.toString());
        record.setSortKey(sortKey);
        record.setEventId(sortKey.substring(sortKey.indexOf('#') + 1));
        record.setEventType("STOCK_ADJUSTED");
        record.setRawPayload("{}");
        record.setMessage("msg");
        record.setOccurredAt(occurredAt);
        record.setRecordedAt(Instant.now());
        return record;
    }
}
