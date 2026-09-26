package com.orderflow.order.saga;

import com.orderflow.order.order.CancellationCode;
import com.orderflow.order.order.Order;
import com.orderflow.order.order.OrderRepository;
import com.orderflow.order.order.OrderStatus;
import com.orderflow.order.saga.messaging.dto.StockReservationFailedEvent;
import com.orderflow.order.saga.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Aplica o resultado da reserva de estoque ao pedido, guardado pelo ESTADO — nunca por uma tabela
 * de mensagens processadas (D-64). {@code SAGA_RESULT_LOCK=order-row}: trava a linha do PEDIDO
 * ({@link OrderRepository#findByIdForUpdate}, {@code PESSIMISTIC_WRITE}), não a {@code
 * company_credit_lock} — liberar crédito ao cancelar não arrisca estourar limite (Claude's
 * Discretion, 05-CONTEXT.md), mas o resultado da reserva e o job de timeout (05-04) disputam o
 * MESMO pedido: sem uma trava comum, um {@code StockReserved} tardio poderia sobrescrever um
 * {@code CANCELLED} já compensado. A trava de linha serializa só as transições da saga, sem
 * bloquear criação de pedidos nem decisões da empresa (que travam {@code company_credit_lock}).
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    private final OrderRepository orderRepository;
    private final OutboxWriter outboxWriter;

    public OrderSagaService(OrderRepository orderRepository, OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.outboxWriter = outboxWriter;
    }

    /**
     * {@code StockReservationFailed} → {@code CANCELLED} (ORD-05), só a partir de {@code
     * RESERVING}. Pedido ausente é consumido e registrado em log (não há o que fazer com ele, e
     * reentregar só o levaria à DLQ). Status diferente de RESERVING é duplicata/resultado tardio
     * — ignorado sem alterar nada (D-64), inclusive se já CANCELLED por um resultado anterior.
     */
    @Transactional
    public void applyReservationFailed(StockReservationFailedEvent event) {
        Optional<Order> maybeOrder = orderRepository.findByIdForUpdate(event.orderId());
        if (maybeOrder.isEmpty()) {
            log.warn("Resultado da saga recebido para pedido inexistente orderId={}", event.orderId());
            return;
        }
        Order order = maybeOrder.get();
        if (order.getStatus() != OrderStatus.RESERVING) {
            log.info("Resultado de falha ignorado (duplicata ou tardio) orderId={} status={}",
                    order.getId(), order.getStatus());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        String reason = CancellationReasons.forFailure(event.reasonCode(), event.failures(), order);
        order.cancel(CancellationCode.valueOf(event.reasonCode()), reason, now);
    }
}
