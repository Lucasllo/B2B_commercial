package com.orderflow.order.saga;

import com.orderflow.order.order.Order;
import com.orderflow.order.saga.messaging.dto.ReserveStockCommand;
import com.orderflow.order.saga.outbox.OutboxWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ponto de entrada ÚNICO da saga de reserva de estoque (D-48) — chamado pela aprovação automática
 * ({@code OrderService}) e pela manual ({@code OrderDecisionService.approve}, Task 2), sempre na
 * mesma transação que adquiriu {@code company_credit_lock}. {@code Propagation.MANDATORY} faz uma
 * chamada fora de transação falhar alto, mesmo motivo de {@code CompanyCreditLocker}: gravar o
 * comando fora da transação que decidiu a aprovação quebraria a atomicidade "nunca pedido avançado
 * sem comando, nem comando sem pedido avançado" (Success Criteria 1 do ROADMAP).
 */
@Component
public class ReservationSagaStarter {

    private final OutboxWriter outboxWriter;

    public ReservationSagaStarter(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void start(Order order, OffsetDateTime now) {
        if (order.getId() == null) {
            throw new IllegalStateException(
                    "Order must be persisted (non-null id) before starting the reservation saga");
        }
        order.startReservation(now);
        UUID eventId = UUID.randomUUID();
        ReserveStockCommand command = ReserveStockCommand.from(order, eventId, now.toInstant());
        outboxWriter.enqueue(command.eventId(), ReserveStockCommand.EVENT_TYPE, order.getId().toString(), command);
    }
}
