package com.orderflow.order.order;

import com.orderflow.order.credit.CompanyCreditLocker;
import com.orderflow.order.order.dto.OrderResponse;
import com.orderflow.order.order.exception.OrderNotFoundException;
import com.orderflow.order.saga.ReservationSagaStarter;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Decisão manual do vendedor sobre um pedido pendente (ORD-03, D-38, D-46). Nenhuma dependência de
 * cliente HTTP (gate de fonte, D-38 — a decisão não reavalia o limite de crédito nem consulta o
 * auth-service, só transiciona o status do pedido já persistido).
 *
 * <p>Passa pela mesma trava da criação ({@link CompanyCreditLocker}, D-46 sobre D-40): sem a
 * trava, uma criação concorrente da mesma empresa poderia somar a exposição antes de esta decisão
 * comitar, ou duas decisões simultâneas sobre o mesmo pedido poderiam ambas enxergar {@code
 * PENDING_APPROVAL}. Depois de adquirir a trava, {@link EntityManager#refresh} relê o pedido — o
 * estado buscado antes da trava pode estar velho se outra decisão acabou de comitar, e sem a
 * releitura a checagem de {@code PENDING_APPROVAL} usaria dado obsoleto.
 *
 * <p>A partir da Fase 5 (D-48), aprovar leva o pedido a {@code RESERVING} com o comando {@code
 * ReserveStock} gravado no outbox, na mesma transação — mesmo ponto de entrada da saga usado pela
 * aprovação automática ({@link com.orderflow.order.order.OrderService}). A resposta continua 200
 * com o pedido já em {@code RESERVING} (D-54).
 */
@Service
public class OrderDecisionService {

    private final OrderRepository orderRepository;
    private final CompanyCreditLocker creditLocker;
    private final EntityManager entityManager;
    private final ReservationSagaStarter sagaStarter;

    public OrderDecisionService(OrderRepository orderRepository, CompanyCreditLocker creditLocker,
                                 EntityManager entityManager, ReservationSagaStarter sagaStarter) {
        this.orderRepository = orderRepository;
        this.creditLocker = creditLocker;
        this.entityManager = entityManager;
        this.sagaStarter = sagaStarter;
    }

    /**
     * Depois de {@code approveManually}, entra na saga (D-48) com o mesmo instante já gravado em
     * {@code decidedAt} — mesma transação que adquiriu a trava da empresa e fez o {@code refresh}.
     * {@link #reject} não muda: rejeição nunca inicia a saga.
     */
    @Transactional
    public OrderResponse approve(UUID orderId, String sellerId, String reason) {
        Order order = decide(orderId, o -> o.approveManually(sellerId, reason, currentInstant()));
        sagaStarter.start(order, order.getDecidedAt());
        return OrderResponse.from(order);
    }

    /**
     * Mesmo fluxo de {@link #approve} (busca → trava → releitura → instante → transição). A
     * rejeição não muda a exposição (D-37), mas passa pela trava assim mesmo: é o que serializa
     * uma aprovação e uma rejeição disparadas juntas no mesmo pedido.
     */
    @Transactional
    public OrderResponse reject(UUID orderId, String sellerId, String reason) {
        Order order = decide(orderId, o -> o.reject(sellerId, reason, currentInstant()));
        return OrderResponse.from(order);
    }

    /**
     * Busca inicial (fora da trava) só serve para descobrir a empresa dona do pedido — a trava e a
     * releitura seguinte garantem que a transição enxergue o estado mais recente, nunca o lido
     * aqui.
     */
    private Order decide(UUID orderId, Consumer<Order> transition) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));

        creditLocker.acquire(order.getCompanyId());
        entityManager.refresh(order);

        transition.accept(order);
        return order;
    }

    /**
     * Instante tomado depois da trava, truncado para microssegundos (precisão do {@code
     * TIMESTAMPTZ} do Postgres) — mesma regra de {@link OrderService#createWithCreditCheck}.
     */
    private OffsetDateTime currentInstant() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
