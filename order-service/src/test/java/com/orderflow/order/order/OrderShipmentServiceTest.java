package com.orderflow.order.order;

import com.orderflow.order.order.dto.OrderResponse;
import com.orderflow.order.order.exception.InvalidOrderTransitionException;
import com.orderflow.order.order.exception.OrderNotFoundException;
import com.orderflow.order.saga.messaging.dto.ShipStockCommand;
import com.orderflow.order.saga.outbox.OutboxWriter;
import com.orderflow.order.shipping.CarrierAssignment;
import com.orderflow.order.shipping.TrackingCodes;
import com.orderflow.order.timeline.OrderTimelineEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Orquestração de expedição e entrega, sem Spring e sem Docker (TEST-01, ORD-10, D-74, D-75, D-76):
 * a linha do pedido é lida sob trava; {@code ship} grava SHIPPED com quem expediu, enfileira o
 * {@code ShipStock} no outbox e a linha do tempo, nessa ordem; transição recusada não enfileira nada;
 * {@code deliver} grava DELIVERED e a linha do tempo sem comando de estoque; pedido inexistente é
 * {@link OrderNotFoundException}.
 */
@ExtendWith(MockitoExtension.class)
class OrderShipmentServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private OrderTimelineEvents orderTimelineEvents;

    private OrderShipmentService service() {
        return new OrderShipmentService(orderRepository, outboxWriter, orderTimelineEvents);
    }

    private static Order orderUntilReserving() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PricedItem item = new PricedItem(1, UUID.randomUUID(), "SKU-1", "Item 1", new BigDecimal("10.00"), 3,
                new BigDecimal("30.00"));
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(item), now);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.approveAutomatically(now);
        order.startReservation(now.plusSeconds(1));
        return order;
    }

    private static Order confirmedOrder() {
        Order order = orderUntilReserving();
        order.confirm(OffsetDateTime.now(ZoneOffset.UTC),
                new CarrierAssignment("Norte Entregas", TrackingCodes.fromDigest(new byte[32])));
        return order;
    }

    private static Order shippedOrder() {
        Order order = confirmedOrder();
        order.ship("seller-0", OffsetDateTime.now(ZoneOffset.UTC));
        return order;
    }

    @Test
    void shipOfAConfirmedOrderRecordsWhoShippedEnqueuesShipStockAndThenTheTimelineEvent() {
        Order order = confirmedOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        OrderResponse response = service().ship(order.getId(), "seller-1");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getShippedBy()).isEqualTo("seller-1");
        assertThat(order.getShippedAt()).isNotNull();
        assertThat(response.id()).isEqualTo(order.getId());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        InOrder inOrder = inOrder(orderRepository, outboxWriter, orderTimelineEvents);
        inOrder.verify(orderRepository).findByIdForUpdate(order.getId());
        inOrder.verify(outboxWriter).enqueue(any(UUID.class), eq(ShipStockCommand.EVENT_TYPE),
                eq(order.getId().toString()), payload.capture());
        inOrder.verify(orderTimelineEvents).shipped(order);
        assertThat(payload.getValue()).isInstanceOfSatisfying(ShipStockCommand.class, command -> {
            assertThat(command.orderId()).isEqualTo(order.getId());
            assertThat(command.reservationId()).isEqualTo(order.getId().toString());
            assertThat(command.items()).hasSize(1);
            assertThat(command.items().get(0).quantity()).isEqualTo(3);
        });
    }

    @Test
    void shipOfAnOrderThatIsNotConfirmedIsRefusedWithoutEnqueuingOrWritingAnEvent() {
        Order order = orderUntilReserving();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service().ship(order.getId(), "seller-1"))
                .isInstanceOfSatisfying(InvalidOrderTransitionException.class, ex -> {
                    assertThat(ex.getFrom()).isEqualTo(OrderStatus.RESERVING);
                    assertThat(ex.getTo()).isEqualTo(OrderStatus.SHIPPED);
                });

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVING);
        verifyNoInteractions(outboxWriter, orderTimelineEvents);
    }

    @Test
    void shipTwiceIsRefusedTheSecondTimeAndEnqueuesNothingMore() {
        Order order = confirmedOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        service().ship(order.getId(), "seller-1");

        assertThatThrownBy(() -> service().ship(order.getId(), "seller-2"))
                .isInstanceOf(InvalidOrderTransitionException.class);

        assertThat(order.getShippedBy()).isEqualTo("seller-1");
        verify(outboxWriter).enqueue(any(UUID.class), eq(ShipStockCommand.EVENT_TYPE), any(), any());
        verify(orderTimelineEvents).shipped(order);
    }

    @Test
    void deliverOfAShippedOrderRecordsWhoDeliveredWritesTheTimelineAndNeverEnqueuesAStockCommand() {
        Order order = shippedOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        service().deliver(order.getId(), "seller-1");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getDeliveredBy()).isEqualTo("seller-1");
        assertThat(order.getDeliveredAt()).isNotNull();
        verify(orderTimelineEvents).delivered(order);
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void deliverOfAnOrderThatIsNotShippedIsRefusedWithoutWritingAnEvent() {
        Order order = confirmedOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service().deliver(order.getId(), "seller-1"))
                .isInstanceOfSatisfying(InvalidOrderTransitionException.class, ex -> {
                    assertThat(ex.getFrom()).isEqualTo(OrderStatus.CONFIRMED);
                    assertThat(ex.getTo()).isEqualTo(OrderStatus.DELIVERED);
                });

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderTimelineEvents, never()).delivered(any());
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void anUnknownOrderIsNotFoundOnShipAndOnDeliver() {
        UUID unknownId = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().ship(unknownId, "seller-1")).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service().deliver(unknownId, "seller-1")).isInstanceOf(OrderNotFoundException.class);

        verifyNoInteractions(outboxWriter, orderTimelineEvents);
    }
}
