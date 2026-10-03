package com.orderflow.order.order;

import com.orderflow.order.credit.CompanyCreditLocker;
import com.orderflow.order.order.dto.OrderResponse;
import com.orderflow.order.order.dto.OrderSummaryResponse;
import com.orderflow.order.order.exception.OrderNotFoundException;
import com.orderflow.order.saga.ReservationSagaStarter;
import com.orderflow.order.timeline.OrderTimelineEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orquestração transacional da criação do pedido, sem Spring e sem Docker (TEST-01): a trava da
 * empresa é adquirida ANTES de ler a exposição (D-40); exposição nula vale zero; a igualdade com o
 * limite aprova (D-36); dentro do limite o pedido é aprovado, a saga começa e a linha do tempo recebe
 * {@code created} e {@code approved} (D-45, D-48, D-78); fora do limite fica PENDING_APPROVAL sem
 * saga (ORD-02); o Correlation-ID da requisição é gravado no pedido (D-95). Também cobre a
 * visibilidade por empresa e a ordenação fixa da listagem (ORD-08, ORD-09, D-47).
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final BigDecimal LIMIT = new BigDecimal("100.00");

    @Mock
    private CompanyCreditLocker creditLocker;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ReservationSagaStarter sagaStarter;

    @Mock
    private OrderTimelineEvents orderTimelineEvents;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private OrderService service() {
        return new OrderService(creditLocker, orderRepository, sagaStarter, orderTimelineEvents);
    }

    private static List<PricedItem> items(String subtotal) {
        BigDecimal value = new BigDecimal(subtotal);
        return List.of(new PricedItem(1, UUID.randomUUID(), "SKU-1", "Item 1", value, 1, value));
    }

    private ArgumentCaptor<Order> createWithExposure(UUID companyId, BigDecimal exposure, String total) {
        when(orderRepository.sumTotalByCompanyIdAndStatusIn(eq(companyId), eq(OrderStatus.CREDIT_CONSUMING)))
                .thenReturn(exposure);
        when(orderRepository.save(any(Order.class))).thenAnswer(returnsFirstArg());

        service().createWithCreditCheck(companyId, "buyer-1", items(total), LIMIT);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        return saved;
    }

    @Test
    void theCompanyLockIsAcquiredBeforeTheExposureIsRead() {
        UUID companyId = UUID.randomUUID();

        createWithExposure(companyId, BigDecimal.ZERO, "10.00");

        InOrder inOrder = inOrder(creditLocker, orderRepository);
        inOrder.verify(creditLocker).acquire(companyId);
        inOrder.verify(orderRepository).sumTotalByCompanyIdAndStatusIn(companyId, OrderStatus.CREDIT_CONSUMING);
        inOrder.verify(orderRepository).save(any(Order.class));
    }

    @Test
    void nullExposureCountsAsZeroAndATotalEqualToTheLimitIsApprovedAutomatically() {
        UUID companyId = UUID.randomUUID();

        ArgumentCaptor<Order> saved = createWithExposure(companyId, null, "100.00");

        Order order = saved.getValue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(order.getDecidedBy()).isEqualTo(Order.SYSTEM_DECIDER);
        InOrder inOrder = inOrder(orderTimelineEvents, sagaStarter);
        inOrder.verify(orderTimelineEvents).created(order);
        inOrder.verify(orderTimelineEvents).approved(order);
        inOrder.verify(sagaStarter).start(eq(order), any(OffsetDateTime.class));
        verify(orderTimelineEvents, never()).pendingApproval(any());
    }

    @Test
    void accumulatedExposurePlusTotalExactlyAtTheLimitIsStillApproved() {
        UUID companyId = UUID.randomUUID();

        ArgumentCaptor<Order> saved = createWithExposure(companyId, new BigDecimal("40.00"), "60.00");

        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.APPROVED);
        verify(sagaStarter).start(eq(saved.getValue()), any(OffsetDateTime.class));
    }

    @Test
    void accumulatedExposurePlusTotalAboveTheLimitHoldsForApprovalWithoutStartingTheSaga() {
        UUID companyId = UUID.randomUUID();

        ArgumentCaptor<Order> saved = createWithExposure(companyId, new BigDecimal("50.00"), "60.00");

        Order order = saved.getValue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_APPROVAL);
        assertThat(order.getDecidedBy()).isNull();
        InOrder inOrder = inOrder(orderTimelineEvents);
        inOrder.verify(orderTimelineEvents).created(order);
        inOrder.verify(orderTimelineEvents).pendingApproval(order);
        verify(orderTimelineEvents, never()).approved(any());
        verify(sagaStarter, never()).start(any(), any());
    }

    @Test
    void theCorrelationIdOfTheRequestIsStoredOnTheCreatedOrder() {
        MDC.put("correlationId", "cid-svc-1");

        ArgumentCaptor<Order> saved = createWithExposure(UUID.randomUUID(), BigDecimal.ZERO, "10.00");

        assertThat(saved.getValue().getCorrelationId()).isEqualTo("cid-svc-1");
    }

    @Test
    void theResponseOfAWithinLimitCreationCarriesTheTotalAndTheItems() {
        UUID companyId = UUID.randomUUID();
        when(orderRepository.sumTotalByCompanyIdAndStatusIn(eq(companyId), eq(OrderStatus.CREDIT_CONSUMING)))
                .thenReturn(BigDecimal.ZERO);
        when(orderRepository.save(any(Order.class))).thenAnswer(returnsFirstArg());

        OrderResponse response = service().createWithCreditCheck(companyId, "buyer-1", items("25.00"), LIMIT);

        assertThat(response.total()).isEqualByComparingTo("25.00");
        assertThat(response.items()).hasSize(1);
    }

    // --- visibilidade por empresa (ORD-08, ORD-09, D-47) -------------------------------------

    private static Order persistedOrder(UUID companyId) {
        Order order = Order.create(companyId, "buyer-1", items("10.00"), OffsetDateTime.now(ZoneOffset.UTC));
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        return order;
    }

    @Test
    void getByIdOfAnotherCompanyIsTheSameNotFoundAsAnUnknownOrder() {
        Order order = persistedOrder(UUID.randomUUID());
        UUID unknownId = UUID.randomUUID();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findById(unknownId)).thenReturn(Optional.empty());
        UUID otherCompany = UUID.randomUUID();

        assertThatThrownBy(() -> service().getById(order.getId(), otherCompany, false))
                .isInstanceOf(OrderNotFoundException.class).hasMessage("Order not found");
        assertThatThrownBy(() -> service().getById(unknownId, otherCompany, false))
                .isInstanceOf(OrderNotFoundException.class).hasMessage("Order not found");
    }

    @Test
    void getByIdIsVisibleToTheOwnerBuyerAndToTheSellerOfAnyCompany() {
        UUID companyId = UUID.randomUUID();
        Order order = persistedOrder(companyId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThat(service().getById(order.getId(), companyId, false).id()).isEqualTo(order.getId());
        assertThat(service().getById(order.getId(), UUID.randomUUID(), true).id()).isEqualTo(order.getId());
    }

    private static Pageable clientPageable() {
        // O cliente tenta ordenar por total: a ordenação do pedido é sempre do servidor.
        return PageRequest.of(2, 7, Sort.by("total"));
    }

    private static Sort fixedSort() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @Test
    void sellerListingUsesFindAllOrFindByStatusWithTheFixedSortAndIgnoresTheClientSort() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        Page<Order> empty = new PageImpl<>(List.of());
        when(orderRepository.findAll(any(Pageable.class))).thenReturn(empty);
        when(orderRepository.findByStatus(eq(OrderStatus.PENDING_APPROVAL), any(Pageable.class))).thenReturn(empty);

        Page<OrderSummaryResponse> all = service().list(null, true, null, clientPageable());
        Page<OrderSummaryResponse> queue = service().list(null, true, OrderStatus.PENDING_APPROVAL, clientPageable());

        assertThat(all.getContent()).isEmpty();
        assertThat(queue.getContent()).isEmpty();
        verify(orderRepository).findAll(pageable.capture());
        assertThat(pageable.getValue().getSort()).isEqualTo(fixedSort());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(7);
        ArgumentCaptor<Pageable> byStatus = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByStatus(eq(OrderStatus.PENDING_APPROVAL), byStatus.capture());
        assertThat(byStatus.getValue().getSort()).isEqualTo(fixedSort());
    }

    @Test
    void buyerListingIsAlwaysScopedToTheCallerCompany() {
        UUID companyId = UUID.randomUUID();
        Page<Order> empty = new PageImpl<>(List.of());
        when(orderRepository.findByCompanyId(eq(companyId), any(Pageable.class))).thenReturn(empty);
        when(orderRepository.findByCompanyIdAndStatus(eq(companyId), eq(OrderStatus.CONFIRMED), any(Pageable.class)))
                .thenReturn(empty);

        service().list(companyId, false, null, clientPageable());
        service().list(companyId, false, OrderStatus.CONFIRMED, clientPageable());

        verify(orderRepository).findByCompanyId(eq(companyId), any(Pageable.class));
        verify(orderRepository).findByCompanyIdAndStatus(eq(companyId), eq(OrderStatus.CONFIRMED), any(Pageable.class));
        verify(orderRepository, never()).findAll(any(Pageable.class));
        verify(orderRepository, never()).findByStatus(any(), any());
    }
}
