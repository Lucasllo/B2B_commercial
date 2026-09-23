package com.orderflow.inventory.stock.messaging;

import com.orderflow.inventory.stock.dto.StockAdjustedEvent;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publicacao direta no SQS, sem Transactional Outbox, por decisao D-29. Chamado apenas depois do
 * commit do ajuste de estoque (pelo {@code InventoryController}, nunca de dentro do metodo
 * transacional de {@code InventoryService}) — o mais proximo que da para chegar da garantia do
 * outbox sem construir o outbox agora. A limitacao de dual-write (Postgres commitado, SQS falha
 * ao publicar) e conhecida e documentada (D-30); a Fase 5 substitui este envio direto pela tabela
 * de outbox onde a saga de fato exige atomicidade.
 */
@Component
public class StockEventPublisher {

    private final SqsTemplate sqsTemplate;
    private final String queueName;

    public StockEventPublisher(SqsTemplate sqsTemplate,
                                @Value("${orderflow.messaging.notification-events-queue}") String queueName) {
        this.sqsTemplate = sqsTemplate;
        this.queueName = queueName;
    }

    public void publishStockAdjusted(UUID productId, int previousQuantityOnHand, int newQuantityOnHand) {
        StockAdjustedEvent event = StockAdjustedEvent.of(productId, previousQuantityOnHand, newQuantityOnHand);
        sqsTemplate.send(to -> to.queue(queueName).payload(event));
    }
}
