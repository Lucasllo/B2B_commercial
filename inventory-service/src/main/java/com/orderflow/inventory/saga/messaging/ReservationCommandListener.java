package com.orderflow.inventory.saga.messaging;

import com.orderflow.inventory.saga.messaging.dto.ReserveStockCommand;
import com.orderflow.inventory.stock.InventoryService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Consumidor da {@code inventory-commands-queue} (D-61) — mesma forma de {@code
 * NotificationEventListener} (Fase 3): parâmetro {@code String} para que o corpo chegue verbatim, e
 * a decisão de como interpretar/validar fica na camada de serviço ({@link SagaCommandParser}).
 *
 * <p>Diverge de {@code NotificationEventListener} num ponto central (D-67): mensagem malformada
 * (falha de {@link SagaCommandParser}) é descartada com log WARN, mas uma falha de <b>negócio</b>
 * (estoque insuficiente, produto sem linha) NÃO é lançada como exceção aqui — {@code
 * InventoryService#reserveAll} já grava o evento de falha no outbox e devolve normalmente; a
 * mensagem é consumida com sucesso (retornar normalmente confirma e apaga a mensagem). Só uma
 * exceção técnica (banco fora, conflito esgotado) propaga sem ser capturada, deixando o SQS
 * reentregar até a DLQ (D-67).
 */
@Component
public class ReservationCommandListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationCommandListener.class);

    private final SagaCommandParser sagaCommandParser;
    private final InventoryService inventoryService;
    private final String queueName;

    public ReservationCommandListener(SagaCommandParser sagaCommandParser, InventoryService inventoryService,
                                       @Value("${orderflow.messaging.inventory-commands-queue}") String queueName) {
        this.sagaCommandParser = sagaCommandParser;
        this.inventoryService = inventoryService;
        this.queueName = queueName;
    }

    @SqsListener("${orderflow.messaging.inventory-commands-queue}")
    public void onMessage(String payload) {
        ReserveStockCommand command;
        try {
            command = sagaCommandParser.parse(payload);
        } catch (InvalidSagaMessageException e) {
            log.warn("Mensagem descartada da fila '{}': {}", queueName, e.getMessage());
            return;
        }
        inventoryService.reserveAll(command.orderId(), command.reservationId(), command.items());
    }
}
