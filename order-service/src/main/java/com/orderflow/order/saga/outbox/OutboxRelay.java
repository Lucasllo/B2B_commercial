package com.orderflow.order.saga.outbox;

import com.orderflow.order.saga.messaging.dto.ReserveStockCommand;
import com.orderflow.order.saga.messaging.dto.ShipStockCommand;
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
 * Publicação transacional do lote (D-59). Único bean do serviço que fala com o SQS — o gate de
 * grep da Task 1 verifica que só este arquivo importa {@code io.awspring.cloud.sqs.operations.}
 * (T-05-01). Ao contrário de {@code CompanyCreditLocker}, que nunca faz I/O de rede sob sua trava
 * (D-41), aqui o envio ao SQS É o ponto da transação (D-59): trava só linhas do outbox durante o
 * envio, nunca trava de pedido ou de crédito.
 *
 * <p>Uma falha de envio de UM evento é capturada só para aquele evento — {@link
 * OutboxEvent#recordFailure} + log WARN citando {@code eventId}/{@code eventType}/fila, nunca o
 * payload (T-05-03) — e o laço segue para os demais (T-05-02): nunca engole a falha sem registrar,
 * e nunca derruba o lote inteiro por um evento que falhou.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Literal porque o DTO {@code ReleaseStockCommand} ainda não existe nesta task (chega em 05-04). */
    private static final String RELEASE_STOCK_EVENT_TYPE = "ReleaseStock";

    private final OutboxEventRepository outboxEventRepository;
    private final SqsOperations sqsOperations;
    private final int batchSize;
    private final String inventoryCommandsQueue;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, SqsOperations sqsOperations,
                        @Value("${orderflow.outbox.batch-size}") int batchSize,
                        @Value("${orderflow.messaging.inventory-commands-queue}") String inventoryCommandsQueue) {
        this.outboxEventRepository = outboxEventRepository;
        this.sqsOperations = sqsOperations;
        this.batchSize = batchSize;
        this.inventoryCommandsQueue = inventoryCommandsQueue;
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

    /**
     * {@code ReserveStock}, {@code ReleaseStock} (D-63) e {@code ShipStock} (D-75, baixa física na
     * expedição) vão para {@code inventory-commands-queue}; qualquer outro {@code eventType} é erro
     * de programação — tratado como falha do próprio evento (nunca derruba o lote), nunca envia
     * para fila nenhuma.
     */
    private String resolveQueue(String eventType) {
        if (ReserveStockCommand.EVENT_TYPE.equals(eventType) || RELEASE_STOCK_EVENT_TYPE.equals(eventType)
                || ShipStockCommand.EVENT_TYPE.equals(eventType)) {
            return inventoryCommandsQueue;
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
