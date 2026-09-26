package com.orderflow.order.saga.messaging;

import com.orderflow.order.saga.OrderSagaService;
import com.orderflow.order.saga.messaging.dto.StockReservationFailedEvent;
import com.orderflow.order.saga.messaging.dto.StockReservedEvent;
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
 * resto propaga para o SQS reentregar até a DLQ (D-67). A partir da Task 2 (05-03),
 * {@link InvalidSagaMessageException} também pode vir de dentro de {@link
 * OrderSagaService#applyStockReserved} ({@code STOCK_RESERVED_ITEMS_CHECK}) — por isso o parse E o
 * despacho para o serviço ficam sob o MESMO try/catch: a transação não grava nada e a mensagem é
 * descartada com o mesmo log WARN, esteja a falha na validação sintática ou na de negócio.
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
        try {
            Object event = sagaEventParser.parse(payload);
            if (event instanceof StockReservationFailedEvent failed) {
                orderSagaService.applyReservationFailed(failed);
            } else if (event instanceof StockReservedEvent reserved) {
                // TODO(05-03 Task 2 RED): wiring comentado de proposito para confirmar RED —
                // restaurado no commit GREEN da mesma task.
            }
        } catch (InvalidSagaMessageException e) {
            log.warn("Mensagem descartada da fila '{}': {}", queueName, e.getMessage());
        }
    }
}
