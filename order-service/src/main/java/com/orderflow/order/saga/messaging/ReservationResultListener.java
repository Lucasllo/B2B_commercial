package com.orderflow.order.saga.messaging;

import com.orderflow.order.saga.OrderSagaService;
import com.orderflow.order.saga.messaging.dto.StockReservationFailedEvent;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Consumidor da {@code order-events-queue} (D-61/D-64) — mesma forma de {@code
 * ReservationCommandListener} do inventory-service: parâmetro {@code String} para que o corpo
 * chegue verbatim, decisão de como interpretar/validar fica em {@link SagaEventParser}. Mensagem
 * malformada é descartada com log WARN (só {@link InvalidSagaMessageException} é capturada); o
 * resto propaga para o SQS reentregar até a DLQ (D-67).
 */
@Component
public class ReservationResultListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationResultListener.class);

    private final SagaEventParser sagaEventParser;
    private final OrderSagaService orderSagaService;
    private final String queueName;

    public ReservationResultListener(SagaEventParser sagaEventParser, OrderSagaService orderSagaService,
                                      @Value("${orderflow.messaging.order-events-queue}") String queueName) {
        this.sagaEventParser = sagaEventParser;
        this.orderSagaService = orderSagaService;
        this.queueName = queueName;
    }

    @SqsListener("${orderflow.messaging.order-events-queue}")
    public void onMessage(String payload) {
        Object event;
        try {
            event = sagaEventParser.parse(payload);
        } catch (InvalidSagaMessageException e) {
            log.warn("Mensagem descartada da fila '{}': {}", queueName, e.getMessage());
            return;
        }
        if (event instanceof StockReservationFailedEvent failed) {
            // TODO(05-03 Task 1 RED): wiring comentado de proposito para confirmar RED —
            // restaurado no commit GREEN da mesma task.
        }
    }
}
