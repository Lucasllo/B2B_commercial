package com.orderflow.order.order;

import com.orderflow.order.credit.CompanyCreditLocker;
import com.orderflow.order.order.dto.OrderResponse;
import com.orderflow.order.order.exception.OrderNotFoundException;
import com.orderflow.order.order.exception.OrderNotPendingException;
import com.orderflow.order.saga.ReservationSagaStarter;
import com.orderflow.order.timeline.OrderTimelineEvents;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Orquestração da decisão manual do vendedor, sem Spring e sem Docker (TEST-01, ORD-03, D-40, D-46,
 * D-48): buscar o pedido, adquirir a trava da empresa dona, reler com {@code refresh} e só então
 * transicionar; aprovar inicia a saga e grava {@code approved}; rejeitar grava {@code rejected} e nunca
 * inicia a saga; pedido inexistente nunca adquire a trava.
 */
@ExtendWith(MockitoExtension.class)
class OrderDecisionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CompanyCreditLocker creditLocker;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ReservationSagaStarter sagaStarter;

    @Mock
    private OrderTimelineEvents orderTimelineEvents;

    private OrderDecisionService service() {
        return new OrderDecisionService(orderRepository, creditLocker, entityManager, sagaStarter,
                orderTimelineEvents);
    }

    private static Order pendingOrder() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PricedItem item = new PricedItem(1, UUID.randomUUID(), "SKU-1", "Item 1", new BigDecimal("10.00"), 1,
                new BigDecimal("10.00"));
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(item), now);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.holdForApproval();
        return order;
    }

    @Test
    void approveFindsLocksRefreshesAndOnlyThenTransitionsAndStartsTheSaga() {
        Order order = pendingOrder();
        List<OrderStatus> statusSeenAtRefresh = new ArrayList<>();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        doAnswer(call -> {
            statusSeenAtRefresh.add(order.getStatus());
            return null;
        }).when(entityManager).refresh(order);

        OrderResponse response = service().approve(order.getId(), "seller-1", "ok");

        InOrder inOrder = inOrder(orderRepository, creditLocker, entityManager, orderTimelineEvents, sagaStarter);
        inOrder.verify(orderRepository).findById(order.getId());
        inOrder.verify(creditLocker).acquire(order.getCompanyId());
        inOrder.verify(entityManager).refresh(order);
        inOrder.verify(orderTimelineEvents).approved(order);
        inOrder.verify(sagaStarter).start(order, order.getDecidedAt());
        // A transição só acontece depois da releitura: no refresh o pedido ainda estava pendente.
        assertThat(statusSeenAtRefresh).containsExactly(OrderStatus.PENDING_APPROVAL);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(order.getDecidedBy()).isEqualTo("seller-1");
        assertThat(order.getReason()).isEqualTo("ok");
        assertThat(response.id()).isEqualTo(order.getId());
        verify(orderTimelineEvents, never()).rejected(any());
    }

    @Test
    void rejectRecordsTheDecisionWritesRejectedAndNeverStartsTheSaga() {
        Order order = pendingOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        service().reject(order.getId(), "seller-1", "sem estoque comercial");

        InOrder inOrder = inOrder(orderRepository, creditLocker, entityManager, orderTimelineEvents);
        inOrder.verify(orderRepository).findById(order.getId());
        inOrder.verify(creditLocker).acquire(order.getCompanyId());
        inOrder.verify(entityManager).refresh(order);
        inOrder.verify(orderTimelineEvents).rejected(order);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getReason()).isEqualTo("sem estoque comercial");
        verify(sagaStarter, never()).start(any(), any());
        verify(orderTimelineEvents, never()).approved(any());
    }

    @Test
    void rejectWithABlankReasonIsRefusedBeforeAnyEventIsWritten() {
        Order order = pendingOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service().reject(order.getId(), "seller-1", " "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_APPROVAL);
        verifyNoInteractions(orderTimelineEvents, sagaStarter);
    }

    @Test
    void approvingAnOrderThatIsNotPendingIsRefusedWithoutStartingTheSagaOrWritingAnEvent() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PricedItem item = new PricedItem(1, UUID.randomUUID(), "SKU-1", "Item 1", new BigDecimal("10.00"), 1,
                new BigDecimal("10.00"));
        Order created = Order.create(UUID.randomUUID(), "buyer-1", List.of(item), now);
        ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
        when(orderRepository.findById(created.getId())).thenReturn(Optional.of(created));

        assertThatThrownBy(() -> service().approve(created.getId(), "seller-1", null))
                .isInstanceOf(OrderNotPendingException.class);

        assertThat(created.getStatus()).isEqualTo(OrderStatus.CREATED);
        verifyNoInteractions(orderTimelineEvents, sagaStarter);
    }

    @Test
    void anUnknownOrderIsNotFoundAndTheCompanyLockIsNeverAcquired() {
        UUID unknownId = UUID.randomUUID();
        when(orderRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().approve(unknownId, "seller-1", null))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service().reject(unknownId, "seller-1", "motivo"))
                .isInstanceOf(OrderNotFoundException.class);

        verifyNoInteractions(creditLocker, entityManager, sagaStarter, orderTimelineEvents);
    }
}
