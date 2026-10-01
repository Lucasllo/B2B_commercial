package com.orderflow.order.saga.outbox;

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
 * evento: uma exceção no envio de um evento nunca impede a publicação dos demais do mesmo lote, e
 * um {@code eventType} desconhecido é tratado como falha do evento, nunca enviado a fila nenhuma.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final String QUEUE_NAME = "inventory-commands-queue";
    private static final int BATCH_SIZE = 20;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private SqsOperations sqsOperations;

    @Test
    void oneFailingSendDoesNotPreventTheOtherTwoEventsOfTheSameBatchFromBeingPublished() {
        OutboxEvent first = pendingEvent("ReserveStock");
        OutboxEvent second = pendingEvent("ReserveStock");
        OutboxEvent third = pendingEvent("ReserveStock");
        when(outboxEventRepository.lockNextBatch(BATCH_SIZE)).thenReturn(List.of(first, second, third));

        doThrow(new RuntimeException("simulated SQS failure"))
                .when(sqsOperations).send(eq(QUEUE_NAME), eq(first.getPayload()));
        when(sqsOperations.send(eq(QUEUE_NAME), eq(second.getPayload()))).thenReturn(null);
        when(sqsOperations.send(eq(QUEUE_NAME), eq(third.getPayload()))).thenReturn(null);

        OutboxRelay relay = new OutboxRelay(outboxEventRepository, sqsOperations, BATCH_SIZE, QUEUE_NAME);
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
    void unknownEventTypeIsRecordedAsFailureWithoutSendingAnything() {
        OutboxEvent unknown = pendingEvent("SomethingElse");
        when(outboxEventRepository.lockNextBatch(BATCH_SIZE)).thenReturn(List.of(unknown));

        OutboxRelay relay = new OutboxRelay(outboxEventRepository, sqsOperations, BATCH_SIZE, QUEUE_NAME);
        int published = relay.publishPendingBatch();

        assertThat(published).isZero();
        assertThat(unknown.getPublishedAt()).isNull();
        assertThat(unknown.getAttempts()).isEqualTo(1);
        assertThat(unknown.getLastError()).isNotBlank();
        verify(sqsOperations, org.mockito.Mockito.never()).send(anyString(), any());
    }

    @Test
    void pendingShipStockEventIsSentToTheInventoryCommandsQueueAndMarkedPublished() {
        OutboxEvent shipStock = pendingEvent("ShipStock");
        when(outboxEventRepository.lockNextBatch(BATCH_SIZE)).thenReturn(List.of(shipStock));
        when(sqsOperations.send(eq(QUEUE_NAME), eq(shipStock.getPayload()))).thenReturn(null);

        OutboxRelay relay = new OutboxRelay(outboxEventRepository, sqsOperations, BATCH_SIZE, QUEUE_NAME);
        int published = relay.publishPendingBatch();

        assertThat(published).isEqualTo(1);
        assertThat(shipStock.getPublishedAt()).isNotNull();
        assertThat(shipStock.getAttempts()).isZero();
        verify(sqsOperations).send(eq(QUEUE_NAME), eq(shipStock.getPayload()));
    }

    @Test
    void relayRequestsExactlyTheConfiguredBatchSizeAndReturnsTheNumberOfEventsPublished() {
        when(outboxEventRepository.lockNextBatch(anyInt())).thenReturn(List.of());

        OutboxRelay relay = new OutboxRelay(outboxEventRepository, sqsOperations, BATCH_SIZE, QUEUE_NAME);
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
