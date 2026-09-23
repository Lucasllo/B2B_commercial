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

        assertThat(record.getProductId()).isEqualTo(productId.toString());
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
                {"eventId":"%s","eventType":"ORDER_CREATED","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"}
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
    void repositoryRuntimeExceptionOnSavePropagatesUnconverted() {
        when(notificationRepository.findByProductId(any())).thenReturn(List.of());
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
        when(notificationRepository.findByProductId(productId.toString()))
                .thenReturn(List.of(later, earlierB, earlierA));

        List<NotificationResponse> history = notificationService.history(productId);

        assertThat(history).extracting(NotificationResponse::eventId)
                .containsExactly(
                        earlierA.getEventId(),
                        earlierB.getEventId(),
                        later.getEventId());
    }

    private NotificationRecord rawRecord(UUID productId, String sortKey, Instant occurredAt) {
        NotificationRecord record = new NotificationRecord();
        record.setProductId(productId.toString());
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
