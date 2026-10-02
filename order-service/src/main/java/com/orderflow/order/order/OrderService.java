package com.orderflow.order.order;

import com.orderflow.order.credit.CompanyCreditLocker;
import com.orderflow.order.credit.CreditPolicy;
import com.orderflow.order.observability.CorrelationContext;
import com.orderflow.order.order.dto.OrderResponse;
import com.orderflow.order.order.dto.OrderSummaryResponse;
import com.orderflow.order.order.exception.OrderNotFoundException;
import com.orderflow.order.saga.ReservationSagaStarter;
import com.orderflow.order.timeline.OrderTimelineEvents;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Único bean deste pacote com métodos {@code @Transactional}. Não depende de nenhum cliente HTTP
 * (D-41) — recebe itens já precificados e o limite de crédito já lido por {@link
 * OrderCreationService}, nunca chama {@code RestClient} daqui.
 */
@Service
public class OrderService {

    private final CompanyCreditLocker creditLocker;
    private final OrderRepository orderRepository;
    private final ReservationSagaStarter sagaStarter;
    private final OrderTimelineEvents orderTimelineEvents;

    public OrderService(CompanyCreditLocker creditLocker, OrderRepository orderRepository,
                         ReservationSagaStarter sagaStarter, OrderTimelineEvents orderTimelineEvents) {
        this.creditLocker = creditLocker;
        this.orderRepository = orderRepository;
        this.sagaStarter = sagaStarter;
        this.orderTimelineEvents = orderTimelineEvents;
    }

    /**
     * Dentro do limite: grava o pedido (para ter o id), entra na saga de reserva de estoque
     * (D-48/D-50) — {@link Order#startReservation} + comando no outbox, mesma transação que
     * adquiriu a trava — e só então monta a resposta, já em {@code RESERVING} (D-54, sem 202 e sem
     * requisição bloqueante).
     */
    @Transactional
    public OrderResponse createWithCreditCheck(UUID companyId, String createdBy, List<PricedItem> pricedItems,
                                                BigDecimal creditLimit) {
        // A trava é adquirida ANTES de qualquer leitura de exposição — serializa a checagem por
        // empresa (D-40); empresas diferentes não se bloqueiam entre si.
        creditLocker.acquire(companyId);

        // Tomado depois da trava: sob concorrência, decidedAt respeita a ordem real de
        // serialização. Truncado para microssegundos — a precisão do TIMESTAMPTZ do Postgres;
        // sem o truncamento, a resposta do POST (montada em memória) e a do GET (relida do banco)
        // divergiriam no último dígito de nanossegundo.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        Order order = Order.create(companyId, createdBy, pricedItems, now);
        order.recordCorrelationId(CorrelationContext.current());

        BigDecimal exposure = orderRepository.sumTotalByCompanyIdAndStatusIn(companyId, OrderStatus.CREDIT_CONSUMING);
        if (exposure == null) {
            exposure = BigDecimal.ZERO;
        }

        if (CreditPolicy.fitsWithinLimit(exposure, order.getTotal(), creditLimit)) {
            order.approveAutomatically(now);
        } else {
            order.holdForApproval();
        }

        order = orderRepository.save(order);

        // D-78/D-79: cada transição persistida grava o seu evento de linha do tempo no outbox, na
        // MESMA transação — "criado" e depois "aprovado" (decisão automática) ou "aguardando
        // aprovação". A entrada em RESERVING não tem evento próprio: ORDER_APPROVED a cobre.
        orderTimelineEvents.created(order);
        if (order.getStatus() == OrderStatus.APPROVED) {
            orderTimelineEvents.approved(order);
            sagaStarter.start(order, now);
        } else {
            orderTimelineEvents.pendingApproval(order);
        }
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(UUID orderId, UUID callerCompanyId, boolean sellerView) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        // Um BUYER de outra empresa recebe o mesmo 404 de um pedido inexistente — nunca revela
        // que o pedido existe (D-47).
        if (!sellerView && !order.getCompanyId().equals(callerCompanyId)) {
            throw new OrderNotFoundException("Order not found");
        }
        return OrderResponse.from(order);
    }

    /**
     * {@code GET /orders} (ORD-08/ORD-09, D-47) — mesmo precedente de {@link
     * com.orderflow.catalog.product.ProductService#list}: o escopo por empresa e a visão de
     * vendedor são decididos aqui, nunca por um parâmetro de query. A ordenação é sempre {@code
     * createdAt}/{@code id} decrescentes; o {@code Sort} do {@code Pageable} recebido é descartado
     * antes da consulta — um {@code sort} de cliente nunca chega ao Spring Data (D-47,
     * 04-RESEARCH.md §Pattern 3). Resumo sem itens: {@link OrderSummaryResponse} não paga consulta
     * extra por pedido.
     *
     * @param callerCompanyId ignorado quando {@code sellerView} é verdadeiro (o vendedor não tem
     *                         escopo de empresa nesta fase, 04-RESEARCH.md Open Question 2)
     * @param status           opcional; quando presente, vale igualmente para BUYER e SELLER_ADMIN —
     *                         para o vendedor é a fila de aprovação (D-47)
     */
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> list(UUID callerCompanyId, boolean sellerView, OrderStatus status,
                                            Pageable pageable) {
        Sort fixedSort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), fixedSort);

        Page<Order> page;
        if (sellerView) {
            page = status == null
                    ? orderRepository.findAll(sortedPageable)
                    : orderRepository.findByStatus(status, sortedPageable);
        } else {
            page = status == null
                    ? orderRepository.findByCompanyId(callerCompanyId, sortedPageable)
                    : orderRepository.findByCompanyIdAndStatus(callerCompanyId, status, sortedPageable);
        }
        return page.map(OrderSummaryResponse::from);
    }
}
