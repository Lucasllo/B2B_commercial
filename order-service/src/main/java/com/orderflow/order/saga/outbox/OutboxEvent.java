package com.orderflow.order.saga.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Linha da tabela {@code outbox_event} ({@code V2__order_reservation_saga.sql}) — Transactional
 * Outbox (D-59, ORD-04). Gravada na MESMA transação da mudança de estado que a origina (via
 * {@code OutboxWriter}), publicada depois pelo {@code OutboxRelay}. Duplicada por padrão no
 * inventory-service (D-62) — sem módulo compartilhado.
 *
 * <p>{@code id} não tem {@code @GeneratedValue}: é o {@code eventId} do payload, atribuído pela
 * aplicação antes de gravar — o consumidor do outro lado da fila usa esse mesmo valor como
 * {@code eventId} da mensagem (SAGA_MESSAGE_CONTRACT), então o id da linha e o id do evento
 * trocado pela fila são sempre o mesmo UUID.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    private static final int LAST_ERROR_MAX_LENGTH = 500;

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = LAST_ERROR_MAX_LENGTH)
    private String lastError;

    public static OutboxEvent pending(UUID id, String eventType, String aggregateId, String payload,
                                       OffsetDateTime createdAt) {
        OutboxEvent event = new OutboxEvent();
        event.id = id;
        event.eventType = eventType;
        event.aggregateId = aggregateId;
        event.payload = payload;
        event.createdAt = createdAt;
        event.attempts = 0;
        return event;
    }

    public void markPublished(OffsetDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    /**
     * Isola a falha de UM evento (T-05-02) — nunca derruba o lote inteiro do relay. Mensagem
     * truncada em {@value #LAST_ERROR_MAX_LENGTH} caracteres (tamanho da coluna); o log de falha do
     * relay cita {@code eventId}/{@code eventType}/fila, nunca o payload (T-05-03).
     */
    public void recordFailure(String error) {
        this.attempts = this.attempts + 1;
        this.lastError = (error != null && error.length() > LAST_ERROR_MAX_LENGTH)
                ? error.substring(0, LAST_ERROR_MAX_LENGTH)
                : error;
    }
}
