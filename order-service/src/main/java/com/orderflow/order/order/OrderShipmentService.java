package com.orderflow.order.order;

import com.orderflow.order.order.dto.OrderResponse;
import com.orderflow.order.order.exception.OrderNotFoundException;
import com.orderflow.order.saga.messaging.dto.ShipStockCommand;
import com.orderflow.order.saga.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Expedição e entrega do pedido pelo vendedor (ORD-10, D-74, D-75, D-76, D-77). Nenhuma dependência
 * de cliente HTTP: só transiciona o status do pedido já persistido.
 *
 * <p>SHIPMENT_LOCK=order-row — {@link #ship} e {@link #deliver} travam a linha do pedido ({@link
 * OrderRepository#findByIdForUpdate}), a mesma trava da saga. Não usam {@code company_credit_lock}:
 * {@code SHIPPED} e {@code DELIVERED} já estão em {@link OrderStatus#CREDIT_CONSUMING} (D-37), então
 * estas transições não mudam a exposição de crédito da empresa e não precisam serializar com a
 * criação de pedidos. Duas expedições simultâneas do mesmo pedido são serializadas pela trava: a
 * segunda enxerga {@code SHIPPED} e recebe {@link
 * com.orderflow.order.order.exception.InvalidOrderTransitionException}.
 *
 * <p>D-75: {@link #ship} grava o comando {@code ShipStock} no outbox na MESMA transação que grava
 * {@code SHIPPED} (sem dual-write, T-06-07); o relay é o único caminho de envio ao SQS. O pedido não
 * espera a baixa física e não existe estado intermediário. {@link #deliver} não grava nada no
 * outbox nesta fase.
 */
@Service
public class OrderShipmentService {

    private final OrderRepository orderRepository;
    private final OutboxWriter outboxWriter;

    public OrderShipmentService(OrderRepository orderRepository, OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public OrderResponse ship(UUID orderId, String sellerId) {
        Order order = lockOrder(orderId);
        OffsetDateTime now = currentInstant();

        order.ship(sellerId, now);

        ShipStockCommand command = ShipStockCommand.from(order, UUID.randomUUID(), now.toInstant());
        outboxWriter.enqueue(command.eventId(), ShipStockCommand.EVENT_TYPE, order.getId().toString(), command);
        return OrderResponse.from(order);
    }

    private Order lockOrder(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
    }

    /** Instante em UTC truncado a microssegundos (precisão do {@code TIMESTAMPTZ}), tomado sob a trava. */
    private OffsetDateTime currentInstant() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
