package com.orderflow.inventory.saga.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * O escritor grava o Correlation-ID do MDC na mesma chamada que serializa o payload (D-94).
 * Duplicado do teste do order-service, pacote do inventory.
 */
@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void enqueueStoresTheMdcCorrelationIdAndTheSerializedPayload() {
        MDC.put("correlationId", "cid-w1");
        UUID eventId = UUID.randomUUID();
        OutboxWriter writer = new OutboxWriter(outboxEventRepository, new ObjectMapper());

        writer.enqueue(eventId, "StockReserved", "agg-1", new Payload("SKU-1"));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isEqualTo("cid-w1");
        assertThat(captor.getValue().getPayload()).contains("\"sku\":\"SKU-1\"");
        assertThat(captor.getValue().getId()).isEqualTo(eventId);
    }

    @Test
    void enqueueWithoutMdcStoresANullCorrelationId() {
        UUID eventId = UUID.randomUUID();
        OutboxWriter writer = new OutboxWriter(outboxEventRepository, new ObjectMapper());

        writer.enqueue(eventId, "StockReserved", "agg-1", new Payload("SKU-2"));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isNull();
    }

    @Test
    void enqueueOfAnUnserializablePayloadFailsWithTheEventIdAndSavesNothing() {
        UUID eventId = UUID.randomUUID();
        OutboxWriter writer = new OutboxWriter(outboxEventRepository, new ObjectMapper());
        Cycle cycle = new Cycle();
        cycle.self = cycle;

        assertThatThrownBy(() -> writer.enqueue(eventId, "StockReserved", "agg-1", cycle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(eventId.toString());
        verify(outboxEventRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private record Payload(String sku) {
    }

    private static final class Cycle {
        public Cycle self;
    }
}
