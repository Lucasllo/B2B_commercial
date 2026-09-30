package com.orderflow.inventory.saga.outbox;

import io.awspring.cloud.sqs.operations.SqsOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes unitários (Mockito, sem Spring, T-05-02) de {@link OutboxRelay} — isolamento de falha por
 * evento (uma exceção no envio de um evento nunca impede a publicação dos demais do mesmo lote),
 * roteamento por {@code eventType} para a fila certa (D-60: {@code StockReserved}/{@code
 * StockReservationFailed} para a fila do order-service, {@code STOCK_ADJUSTED} para a do
 * notification-service, a partir de 05-04) e {@code eventType} desconhecido tratado como falha do
 * evento, nunca enviado a fila nenhuma. Cópia do equivalente do order-service (05-01) com as rotas
 * do inventory-service.
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
                .when(sqsOperations).send(eq(ORDER_EVENTS_QUEUE), eq(first.getPayload()));
        when(sqsOperations.send(eq(ORDER_EVENTS_QUEUE), eq(second.getPayload()))).thenReturn(null);
        when(sqsOperations.send(eq(ORDER_EVENTS_QUEUE), eq(third.getPayload()))).thenReturn(null);

        OutboxRelay relay = new OutboxRelay(
                outboxEventRepository, sqsOperations, BATCH_SIZE, ORDER_EVENTS_QUEUE, NOTIFICATION_EVENTS_QUEUE);
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

        OutboxRelay relay = new OutboxRelay(
                outboxEventRepository, sqsOperations, BATCH_SIZE, ORDER_EVENTS_QUEUE, NOTIFICATION_EVENTS_QUEUE);
        int published = relay.publishPendingBatch();

        assertThat(published).isEqualTo(3);
        verify(sqsOperations).send(ORDER_EVENTS_QUEUE, reserved.getPayload());
        verify(sqsOperations).send(ORDER_EVENTS_QUEUE, failed.getPayload());
        verify(sqsOperations).send(NOTIFICATION_EVENTS_QUEUE, adjusted.getPayload());
    }

    @Test
    void unknownEventTypeIsRecordedAsFailureWithoutSendingAnything() {
        OutboxEvent unknown = pendingEvent("SomethingElse");
        when(outboxEventRepository.lockNextBatch(BATCH_SIZE)).thenReturn(List.of(unknown));

        OutboxRelay relay = new OutboxRelay(
                outboxEventRepository, sqsOperations, BATCH_SIZE, ORDER_EVENTS_QUEUE, NOTIFICATION_EVENTS_QUEUE);
        int published = relay.publishPendingBatch();

        assertThat(published).isZero();
        assertThat(unknown.getPublishedAt()).isNull();
        assertThat(unknown.getAttempts()).isEqualTo(1);
        assertThat(unknown.getLastError()).isNotBlank();
        verify(sqsOperations, org.mockito.Mockito.never()).send(anyString(), any());
    }

    @Test
    void relayRequestsExactlyTheConfiguredBatchSizeAndReturnsTheNumberOfEventsPublished() {
        when(outboxEventRepository.lockNextBatch(anyInt())).thenReturn(List.of());

        OutboxRelay relay = new OutboxRelay(
                outboxEventRepository, sqsOperations, BATCH_SIZE, ORDER_EVENTS_QUEUE, NOTIFICATION_EVENTS_QUEUE);
        int published = relay.publishPendingBatch();

        assertThat(published).isZero();
        verify(outboxEventRepository).lockNextBatch(BATCH_SIZE);
    }

    private OutboxEvent pendingEvent(String eventType) {
        UUID id = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + id + "\",\"eventType\":\"" + eventType + "\"}";
        return OutboxEvent.pending(id, eventType, UUID.randomUUID().toString(), payload,
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
