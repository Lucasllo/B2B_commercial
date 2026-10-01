package com.orderflow.order.timeline;

import com.orderflow.order.order.Order;
import com.orderflow.order.saga.outbox.OutboxWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Escritor dos oito eventos de linha do tempo do pedido (D-78, D-79). Cada método grava UM evento
 * no outbox, na MESMA transação que persistiu a transição — {@code Propagation.MANDATORY} faz uma
 * chamada fora de transação falhar alto, mesmo motivo de {@link
 * com.orderflow.order.saga.ReservationSagaStarter}: gravar o evento numa transação diferente da
 * mudança de status faria a linha do tempo mostrar uma transição que não foi persistida (ou omitir
 * uma que foi).
 *
 * <p>Este componente NUNCA fala com o SQS: o {@code OutboxRelay} é o único caminho de envio (Key
 * Decision da Fase 5, T-06-19). {@code eventId} = id da linha do outbox, como nos comandos da saga.
 * A entrada em {@code RESERVING} não tem evento próprio (D-78): fica coberta por {@code
 * ORDER_APPROVED}.
 */
@Component
public class OrderTimelineEvents {

    private final OutboxWriter outboxWriter;

    public OrderTimelineEvents(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void created(Order order) {
        enqueue(order, OrderLifecycleEvent.ORDER_CREATED, OrderLifecycleEvent::created);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void pendingApproval(Order order) {
        enqueue(order, OrderLifecycleEvent.ORDER_PENDING_APPROVAL, OrderLifecycleEvent::pendingApproval);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(Order order) {
        enqueue(order, OrderLifecycleEvent.ORDER_APPROVED, OrderLifecycleEvent::approved);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rejected(Order order) {
        enqueue(order, OrderLifecycleEvent.ORDER_REJECTED, OrderLifecycleEvent::rejected);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmed(Order order) {
        enqueue(order, OrderLifecycleEvent.ORDER_CONFIRMED, OrderLifecycleEvent::confirmed);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelled(Order order) {
        enqueue(order, OrderLifecycleEvent.ORDER_CANCELLED, OrderLifecycleEvent::cancelled);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void shipped(Order order) {
        enqueue(order, OrderLifecycleEvent.ORDER_SHIPPED, OrderLifecycleEvent::shipped);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void delivered(Order order) {
        enqueue(order, OrderLifecycleEvent.ORDER_DELIVERED, OrderLifecycleEvent::delivered);
    }

    private void enqueue(Order order, String eventType, BiFunction<Order, UUID, OrderLifecycleEvent> factory) {
        if (order.getId() == null) {
            throw new IllegalStateException(
                    "Order must be persisted (non-null id) before writing a timeline event");
        }
        UUID eventId = UUID.randomUUID();
        OrderLifecycleEvent event = factory.apply(order, eventId);
        outboxWriter.enqueue(eventId, eventType, order.getId().toString(), event);
    }
}
