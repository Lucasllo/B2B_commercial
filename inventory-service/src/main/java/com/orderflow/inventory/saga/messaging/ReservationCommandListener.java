package com.orderflow.inventory.saga.messaging;

import com.orderflow.inventory.observability.CorrelationContext;
import com.orderflow.inventory.saga.messaging.dto.ReleaseStockCommand;
import com.orderflow.inventory.saga.messaging.dto.ReserveStockCommand;
import com.orderflow.inventory.saga.messaging.dto.ShipStockCommand;
import com.orderflow.inventory.stock.InventoryService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumidor da {@code inventory-commands-queue} (D-61) — mesma forma de {@code
 * NotificationEventListener} (Fase 3): parâmetro {@code String} para que o corpo chegue verbatim, e
 * a decisão de como interpretar/validar fica na camada de serviço ({@link SagaCommandParser}).
 * Despacha para {@link InventoryService#reserveAll}, {@link InventoryService#releaseAll} ou {@link
 * InventoryService#shipAll} conforme o tipo devolvido pelo parser (D-63, D-66, 05-04; D-75, 06-03)
 * — {@code ReleaseStock} e {@code ShipStock} não geram nenhuma resposta (o order-service não espera
 * resultado da compensação nem da baixa física).
 *
 * <p>Diverge de {@code NotificationEventListener} num ponto central (D-67): mensagem malformada
 * (falha de {@link SagaCommandParser}) é descartada com log WARN — exceto {@code ShipStock} inválido
 * ({@link InvalidShipStockException}, D-107/WR-01), que é anomalia técnica: loga ERROR e relança para
 * o SQS reentregar até a DLQ, porque descartá-lo perderia a baixa física de um pedido já SHIPPED.
 * Uma falha de <b>negócio</b>
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

    /**
     * Compatibilidade de testes unitários que chamam o listener sem o atributo SQS (Pitfall 5,
     * {@code LISTENER_COMPAT_OVERLOAD}). Sem {@code @SqsListener}: o container registra só o método
     * anotado.
     */
    public void onMessage(String payload) {
        onMessage(payload, null);
    }

    @SqsListener("${orderflow.messaging.inventory-commands-queue}")
    public void onMessage(String payload,
                          @Header(name = CorrelationContext.SQS_ATTRIBUTE, required = false) String correlationId) {
        try (var scope = CorrelationContext.open(correlationId)) {
            log.info("Mensagem recebida da fila '{}'", queueName);
            Object command;
            try {
                command = sagaCommandParser.parse(payload);
            } catch (InvalidShipStockException e) {
                // D-107/WR-01: ShipStock so existe para pedido ja SHIPPED — descartar perderia a baixa
                // fisica em silencio. Anomalia tecnica: ERROR + relanca (reentrega -> DLQ). So orderId
                // sanitizado e a mensagem de validacao vao ao log, nunca o payload (T-05-03).
                log.error("ShipStock invalido enviado para reentrega/DLQ orderId={} fila='{}': {}",
                        e.getOrderIdForLog(), queueName, e.getMessage());
                throw e;
            } catch (InvalidSagaMessageException e) {
                log.warn("Mensagem descartada da fila '{}': {}", queueName, e.getMessage());
                return;
            }
            if (command instanceof ReserveStockCommand reserveStock) {
                inventoryService.reserveAll(reserveStock.orderId(), reserveStock.reservationId(), reserveStock.items());
                return;
            }
            if (command instanceof ReleaseStockCommand releaseStock) {
                inventoryService.releaseAll(releaseStock.orderId(), releaseStock.reservationId(), releaseStock.items());
                return;
            }
            if (command instanceof ShipStockCommand shipStock) {
                // Baixa fisica pelo livro (D-75) — sem resposta; anomalia tecnica propaga (reentrega -> DLQ).
                inventoryService.shipAll(shipStock.orderId(), shipStock.reservationId(), shipStock.items());
            }
        }
    }
}
