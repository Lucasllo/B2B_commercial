package com.orderflow.inventory.saga.outbox;

import com.orderflow.inventory.observability.CorrelationContext;
import io.awspring.cloud.sqs.operations.SqsOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes unitários (Mockito, sem Spring, T-05-02) de {@link OutboxRelay} — isolamento de falha por
 * evento, roteamento por tipo (D-60) e o message attribute {@code correlationId} lido da linha do
 * outbox (D-94). O envio é {@code send(String, Message)}: o corpo comparado por {@code getPayload()}.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final String ORDER_EVENTS_QUEUE = "order-events-queue";
    private static final String NOTIFICATION_EVENTS_QUEUE = "notification-events-queue";
    private static final int BATCH_SIZE = 20;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private SqsOperations sqsOperations;

    @Test
    void oneFailingSendDoesNotPreventTheOtherTwoEventsOfTheSameBatchFromBeingPublished() {
        OutboxEvent first = pendingEvent("StockReserved");
        OutboxEvent second = pendingEvent("StockReserved");
        OutboxEvent third = pendingEvent("StockReserved");
        when(outboxEventRepository.lockNextBatch(BATCH_SIZE)).thenReturn(List.of(first, second, third));

        doThrow(new RuntimeException("simulated SQS failure"))
                .when(sqsOperations).send(eq(ORDER_EVENTS_QUEUE), payloadEq(first.getPayload()));
        when(sqsOperations.send(eq(ORDER_EVENTS_QUEUE), payloadEq(second.getPayload()))).thenReturn(null);
        when(sqsOperations.send(eq(ORDER_EVENTS_QUEUE), payloadEq(third.getPayload()))).thenReturn(null);

        OutboxRelay relay = newRelay();
        int published = relay.publishPendingBatch();

        assertThat(published).isEqualTo(2);
        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).isNotBlank();
        assertThat(second.getPublishedAt()).isNotNull();
        assertThat(second.getAttempts()).isZero();
        assertThat(third.getPublishedAt()).isNotNull();
        assertThat(third.getAttempts()).isZero();
    }

    @Test
    void stockReservedAndStockReservationFailedGoToOrderEventsQueueAndStockAdjustedGoesToNotificationEventsQueue() {
        OutboxEvent reserved = pendingEvent("StockReserved");
        OutboxEvent failed = pendingEvent("StockReservationFailed");
        OutboxEvent adjusted = pendingEvent("STOCK_ADJUSTED");
        when(outboxEventRepository.lockNextBatch(BATCH_SIZE)).thenReturn(List.of(reserved, failed, adjusted));

        OutboxRelay relay = newRelay();
        int published = relay.publishPendingBatch();

        assertThat(published).isEqualTo(3);
        assertThat(captureSent(ORDER_EVENTS_QUEUE, reserved.getPayload()).getPayload())
                .isEqualTo(reserved.getPayload());
        assertThat(captureSent(ORDER_EVENTS_QUEUE, failed.getPayload()).getPayload())
                .isEqualTo(failed.getPayload());
        assertThat(captureSent(NOTIFICATION_EVENTS_QUEUE, adjusted.getPayload()).getPayload())
                .isEqualTo(adjusted.getPayload());
    }

    @Test
    void unknownEventTypeIsRecordedAsFailureWithoutSendingAnything() {
        OutboxEvent unknown = pendingEvent("SomethingElse");
        when(outboxEventRepository.lockNextBatch(BATCH_SIZE)).thenReturn(List.of(unknown));

        OutboxRelay relay = newRelay();
        int published = relay.publishPendingBatch();

        assertThat(published).isZero();
        assertThat(unknown.getPublishedAt()).isNull();
        assertThat(unknown.getAttempts()).isEqualTo(1);
        assertThat(unknown.getLastError()).isNotBlank();
        verify(sqsOperations, never()).send(anyString(), any());
    }

    @Test
    void relayRequestsExactlyTheConfiguredBatchSizeAndReturnsTheNumberOfEventsPublished() {
        when(outboxEventRepository.lockNextBatch(anyInt())).thenReturn(List.of());

        OutboxRelay relay = newRelay();
        int published = relay.publishPendingBatch();

        assertThat(published).isZero();
        verify(outboxEventRepository).lockNextBatch(BATCH_SIZE);
    }

    @Test
    void eventWithCorrelationIdSendsItAsMessageHeader() {
        OutboxEvent event = pendingEvent("StockReserved", "cid-inv-1");
        when(outboxEventRepository.lockNextBatch(BATCH_SIZE)).thenReturn(List.of(event));
        when(sqsOperations.send(eq(ORDER_EVENTS_QUEUE), payloadEq(event.getPayload()))).thenReturn(null);

        int published = newRelay().publishPendingBatch();

        assertThat(published).isEqualTo(1);
        Message<String> sent = captureSent(ORDER_EVENTS_QUEUE);
        assertThat(sent.getPayload()).isEqualTo(event.getPayload());
        assertThat(sent.getHeaders().get(CorrelationContext.SQS_ATTRIBUTE)).isEqualTo("cid-inv-1");
    }

    @Test
    void eventWithoutCorrelationIdSendsMessageWithoutThatHeader() {
        OutboxEvent event = pendingEvent("StockReserved", null);
        when(outboxEventRepository.lockNextBatch(BATCH_SIZE)).thenReturn(List.of(event));
        when(sqsOperations.send(eq(ORDER_EVENTS_QUEUE), payloadEq(event.getPayload()))).thenReturn(null);

        int published = newRelay().publishPendingBatch();

        assertThat(published).isEqualTo(1);
        Message<String> sent = captureSent(ORDER_EVENTS_QUEUE);
        assertThat(sent.getPayload()).isEqualTo(event.getPayload());
        assertThat(sent.getHeaders().containsKey(CorrelationContext.SQS_ATTRIBUTE)).isFalse();
    }

    private OutboxRelay newRelay() {
        return new OutboxRelay(outboxEventRepository, sqsOperations, BATCH_SIZE, ORDER_EVENTS_QUEUE,
                NOTIFICATION_EVENTS_QUEUE);
    }

    private OutboxEvent pendingEvent(String eventType) {
        return pendingEvent(eventType, null);
    }

    private OutboxEvent pendingEvent(String eventType, String correlationId) {
        UUID id = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + id + "\",\"eventType\":\"" + eventType + "\"}";
        return OutboxEvent.pending(id, eventType, UUID.randomUUID().toString(), payload,
                OffsetDateTime.now(ZoneOffset.UTC), correlationId);
    }

    @SuppressWarnings("unchecked")
    private Message<String> payloadEq(String payload) {
        return org.mockito.ArgumentMatchers.argThat(message ->
                message != null && payload.equals(message.getPayload()));
    }

    @SuppressWarnings("unchecked")
    private Message<String> captureSent(String queueName) {
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(sqsOperations).send(eq(queueName), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Message<String> captureSent(String queueName, String payload) {
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(sqsOperations, org.mockito.Mockito.atLeastOnce()).send(eq(queueName), captor.capture());
        return captor.getAllValues().stream()
                .filter(message -> payload.equals(message.getPayload()))
                .findFirst()
                .orElseThrow();
    }
}
