package com.orderflow.inventory.stock.messaging;

import com.orderflow.inventory.stock.dto.StockAdjustedEvent;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    private static final Logger log = LoggerFactory.getLogger(StockEventPublisher.class);

    private final SqsTemplate sqsTemplate;
    private final String queueName;

    public StockEventPublisher(SqsTemplate sqsTemplate,
                                @Value("${orderflow.messaging.notification-events-queue}") String queueName) {
        this.sqsTemplate = sqsTemplate;
        this.queueName = queueName;
    }

    /**
     * A transacao do ajuste ja foi commitada quando este metodo roda — falhar a requisicao aqui
     * diria ao vendedor que o ajuste nao aconteceu quando ele aconteceu (03-RESEARCH.md
     * Assumption A4). Por isso a falha de envio e capturada e registrada, nunca relancada: o
     * risco de dual-write fica sem mitigacao tecnica nesta fase (D-30), mas cada evento perdido
     * deixa uma linha ERROR identificavel, nunca silenciosa. Sem nova tentativa de envio nem fila
     * local de pendencias — qualquer mecanismo de reenvio seria, na pratica, um outbox parcial, e
     * D-29 decide que o outbox so entra na Fase 5.
     */
    public void publishStockAdjusted(UUID productId, int previousQuantityOnHand, int newQuantityOnHand,
                                      Instant adjustedAt) {
        StockAdjustedEvent event = StockAdjustedEvent.of(productId, previousQuantityOnHand, newQuantityOnHand, adjustedAt);
        try {
            sqsTemplate.send(to -> to.queue(queueName).payload(event));
        } catch (RuntimeException e) {
            log.error("Falha ao publicar evento {} (eventId={}) do produto {} na fila '{}' — o "
                            + "ajuste de estoque ja foi gravado no banco, mas este evento se "
                            + "perdeu (limitacao conhecida da Fase 3, D-30; resolvida na Fase 5 "
                            + "pelo Transactional Outbox)",
                    event.eventType(), event.eventId(), productId, queueName, e);
        }
    }
}
