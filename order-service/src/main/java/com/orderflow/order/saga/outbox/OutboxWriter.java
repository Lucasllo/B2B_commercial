package com.orderflow.order.saga.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.observability.CorrelationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Escritor do outbox (D-59) — serializa o payload UMA única vez, no momento da escrita, e grava a
 * linha na mesma transação de negócio. {@code Propagation.MANDATORY} faz uma chamada fora de
 * transação falhar alto, mesmo motivo de {@code CompanyCreditLocker}: gravar fora de uma transação
 * já aberta pela mudança de estado quebraria a atomicidade que o outbox existe para garantir.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID eventId, String eventType, String aggregateId, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Falha de serialização é erro de programação (o payload é montado por este próprio
            // serviço) — nunca um evento perdido em silêncio.
            throw new IllegalStateException("Failed to serialize outbox payload for event " + eventId, e);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        outboxEventRepository.save(OutboxEvent.pending(eventId, eventType, aggregateId, json, now,
                CorrelationContext.current()));
    }
}
