package com.orderflow.inventory.saga.outbox;

import com.orderflow.inventory.saga.messaging.dto.StockReservationFailedEvent;
import com.orderflow.inventory.saga.messaging.dto.StockReservedEvent;
import com.orderflow.inventory.stock.dto.StockAdjustedEvent;
import io.awspring.cloud.sqs.operations.SqsOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Publicação transacional do lote (D-59) — duplicado do relay equivalente do order-service (D-62).
 * Único bean deste serviço que fala com o SQS (D-60, 05-04) — a partir desta fase o gate de grep
 * verifica que este é o ÚNICO arquivo de produção que importa {@code
 * io.awspring.cloud.sqs.operations.}: o publicador direto legado ({@code StockEventPublisher}, D-29,
 * dual-write da Fase 3) foi removido, fechando a limitação D-29/D-30 (T-05-01). Ao contrário de
 * {@code InventoryService.reserveAll}, que nunca faz I/O de rede sob sua transação de reserva, aqui
 * o envio ao SQS É o ponto da transação (D-59).
 *
 * <p>Uma falha de envio de UM evento é capturada só para aquele evento — {@link
 * OutboxEvent#recordFailure} + log WARN citando {@code eventId}/{@code eventType}/fila, nunca o
 * payload (T-05-03) — e o laço segue para os demais (T-05-02): nunca engole a falha sem registrar,
 * e nunca derruba o lote inteiro por um evento que falhou.
 *
 * <p>{@code resolveQueue} despacha por {@code eventType}: {@code StockReserved}/
 * {@code StockReservationFailed} vão para {@code order-events-queue}; {@code STOCK_ADJUSTED} (D-60,
 * gravado por {@code InventoryService#setStock} desde 05-04) vai para {@code
 * notification-events-queue}; qualquer outro é erro de programação, tratado como falha do próprio
 * evento.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxEventRepository;
    private final SqsOperations sqsOperations;
    private final int batchSize;
    private final String orderEventsQueue;
    private final String notificationEventsQueue;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, SqsOperations sqsOperations,
                        @Value("${orderflow.outbox.batch-size}") int batchSize,
                        @Value("${orderflow.messaging.order-events-queue}") String orderEventsQueue,
                        @Value("${orderflow.messaging.notification-events-queue}") String notificationEventsQueue) {
        this.outboxEventRepository = outboxEventRepository;
        this.sqsOperations = sqsOperations;
        this.batchSize = batchSize;
        this.orderEventsQueue = orderEventsQueue;
        this.notificationEventsQueue = notificationEventsQueue;
    }

    @Transactional
    public int publishPendingBatch() {
        List<OutboxEvent> batch = outboxEventRepository.lockNextBatch(batchSize);
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                String queueName = resolveQueue(event.getEventType());
                sqsOperations.send(queueName, event.getPayload());
                event.markPublished(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
                published++;
            } catch (RuntimeException e) {
                log.warn("Falha ao publicar evento outbox eventId={} eventType={} na fila '{}' — "
                                + "tentativa {} registrada, será reprocessado no próximo ciclo do relay",
                        event.getId(), event.getEventType(), safeQueueNameFor(event.getEventType()),
                        event.getAttempts() + 1, e);
                event.recordFailure(e.getMessage());
            }
        }
        return published;
    }

    private String resolveQueue(String eventType) {
        if (StockReservedEvent.EVENT_TYPE.equals(eventType) || StockReservationFailedEvent.EVENT_TYPE.equals(eventType)) {
            return orderEventsQueue;
        }
        if (StockAdjustedEvent.EVENT_TYPE.equals(eventType)) {
            return notificationEventsQueue;
        }
        throw new IllegalStateException("No queue configured for outbox eventType '" + eventType + "'");
    }

    private String safeQueueNameFor(String eventType) {
        try {
            return resolveQueue(eventType);
        } catch (IllegalStateException e) {
            return "unknown";
        }
    }
}
