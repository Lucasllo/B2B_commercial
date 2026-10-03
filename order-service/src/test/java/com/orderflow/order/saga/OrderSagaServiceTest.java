package com.orderflow.order.saga;

import com.orderflow.order.order.CancellationCode;
import com.orderflow.order.order.Order;
import com.orderflow.order.order.OrderRepository;
import com.orderflow.order.order.OrderStatus;
import com.orderflow.order.order.PricedItem;
import com.orderflow.order.saga.messaging.InvalidSagaMessageException;
import com.orderflow.order.saga.messaging.dto.ReleaseStockCommand;
import com.orderflow.order.saga.messaging.dto.ReservationFailureLine;
import com.orderflow.order.saga.messaging.dto.ReservationLine;
import com.orderflow.order.saga.messaging.dto.StockReservationFailedEvent;
import com.orderflow.order.saga.messaging.dto.StockReservedEvent;
import com.orderflow.order.saga.outbox.OutboxWriter;
import com.orderflow.order.shipping.CarrierAssignment;
import com.orderflow.order.shipping.CarrierGateway;
import com.orderflow.order.shipping.TrackingCodes;
import com.orderflow.order.timeline.OrderTimelineEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Aplicação do resultado da saga e do timeout, sem Spring e sem Docker (TEST-01, ORD-05, ORD-06, D-57,
 * D-63, D-64, D-70): o resultado só é aplicado se o pedido estiver em RESERVING (guarda por estado);
 * duplicata e resultado tardio não repetem efeito; sucesso tardio em pedido CANCELLED grava
 * {@code ReleaseStock}; a confirmação atribui transportadora na mesma transação; itens divergentes do
 * pedido são descartados; o timeout só cancela pedido vencido, grava {@code ReleaseStock} e reabre o
 * Correlation-ID de criação (D-95).
 */
@ExtendWith(MockitoExtension.class)
class OrderSagaServiceTest {

    private static final CarrierAssignment ASSIGNMENT =
            new CarrierAssignment("Norte Entregas", TrackingCodes.fromDigest(new byte[32]));

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private CarrierGateway carrierGateway;

    @Mock
    private OrderTimelineEvents orderTimelineEvents;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private OrderSagaService service() {
        return new OrderSagaService(orderRepository, outboxWriter, carrierGateway, orderTimelineEvents);
    }

    private static Order reservingOrder(OffsetDateTime reservationStartedAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PricedItem item = new PricedItem(1, UUID.randomUUID(), "SKU-1", "Item 1", new BigDecimal("10.00"), 2,
                new BigDecimal("20.00"));
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(item), now);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.approveAutomatically(now);
        order.startReservation(reservationStartedAt);
        return order;
    }

    private static Order reservingOrder() {
        return reservingOrder(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static StockReservedEvent reservedEventFor(Order order) {
        List<ReservationLine> lines = order.getItems().stream()
                .map(item -> new ReservationLine(item.getProductId(), item.getQuantity()))
                .toList();
        return new StockReservedEvent(UUID.randomUUID(), StockReservedEvent.EVENT_TYPE, Instant.now(),
                order.getId(), order.getId().toString(), lines);
    }

    private static StockReservationFailedEvent insufficientStockFor(Order order) {
        UUID productId = order.getItems().get(0).getProductId();
        return new StockReservationFailedEvent(UUID.randomUUID(), StockReservationFailedEvent.EVENT_TYPE,
                Instant.now(), order.getId(), order.getId().toString(),
                StockReservationFailedEvent.INSUFFICIENT_STOCK,
                List.of(new ReservationFailureLine(productId, 2, 1)));
    }

    // --- falha de reserva -------------------------------------------------------------------

    @Test
    void failureWhileReservingCancelsWithTheCodeAndAReadableReasonAndWritesTheTimelineEvent() {
        Order order = reservingOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        service().applyReservationFailed(insufficientStockFor(order));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationCode()).isEqualTo(CancellationCode.INSUFFICIENT_STOCK);
        assertThat(order.getCancellationReason()).startsWith("Estoque insuficiente").contains("SKU-1");
        assertThat(order.getCancelledAt()).isNotNull();
        verify(orderTimelineEvents).cancelled(order);
        verifyNoInteractions(carrierGateway, outboxWriter);
    }

    @Test
    void duplicateFailureOnAnAlreadyCancelledOrderIsANoOp() {
        Order order = reservingOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        service().applyReservationFailed(insufficientStockFor(order));
        OffsetDateTime cancelledAt = order.getCancelledAt();

        service().applyReservationFailed(insufficientStockFor(order));

        assertThat(order.getCancelledAt()).isEqualTo(cancelledAt);
        verify(orderTimelineEvents).cancelled(order);
    }

    @Test
    void lateFailureAfterConfirmationNeverCancelsTheOrder() {
        Order order = reservingOrder();
        order.confirm(OffsetDateTime.now(ZoneOffset.UTC), ASSIGNMENT);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        service().applyReservationFailed(insufficientStockFor(order));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verifyNoInteractions(orderTimelineEvents);
    }

    @Test
    void failureForAnUnknownOrderIsIgnored() {
        UUID unknownId = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(unknownId)).thenReturn(Optional.empty());
        StockReservationFailedEvent event = new StockReservationFailedEvent(UUID.randomUUID(),
                StockReservationFailedEvent.EVENT_TYPE, Instant.now(), unknownId, unknownId.toString(),
                StockReservationFailedEvent.RESERVATION_CANCELLED, List.of());

        service().applyReservationFailed(event);

        verifyNoInteractions(orderTimelineEvents, outboxWriter, carrierGateway);
    }

    // --- sucesso de reserva -----------------------------------------------------------------

    @Test
    void successWhileReservingConfirmsWithTheAssignedCarrierAndTrackingAndWritesTheTimelineEvent() {
        Order order = reservingOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(carrierGateway.assign(order.getId())).thenReturn(ASSIGNMENT);

        service().applyStockReserved(reservedEventFor(order));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getCarrier()).isEqualTo("Norte Entregas");
        assertThat(order.getTrackingCode()).isEqualTo(ASSIGNMENT.trackingCode());
        assertThat(order.getConfirmedAt()).isNotNull();
        verify(orderTimelineEvents).confirmed(order);
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void duplicateSuccessOnAConfirmedOrderNeverReassignsTheCarrierNorWritesAnotherEvent() {
        Order order = reservingOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(carrierGateway.assign(order.getId())).thenReturn(ASSIGNMENT);
        service().applyStockReserved(reservedEventFor(order));

        service().applyStockReserved(reservedEventFor(order));

        verify(carrierGateway).assign(order.getId());
        verify(orderTimelineEvents).confirmed(order);
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void lateSuccessOnACancelledOrderWritesReleaseStockWithTheLateReasonAndNeverAssignsACarrier() {
        Order order = reservingOrder();
        order.cancel(CancellationCode.INSUFFICIENT_STOCK, "x", OffsetDateTime.now(ZoneOffset.UTC));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        service().applyStockReserved(reservedEventFor(order));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).enqueue(any(UUID.class), eq(ReleaseStockCommand.EVENT_TYPE),
                eq(order.getId().toString()), payload.capture());
        assertThat(payload.getValue()).isInstanceOfSatisfying(ReleaseStockCommand.class, command -> {
            assertThat(command.reason()).isEqualTo(ReleaseStockCommand.LATE_RESERVATION);
            assertThat(command.reservationId()).isEqualTo(order.getId().toString());
        });
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCarrier()).isNull();
        verifyNoInteractions(carrierGateway, orderTimelineEvents);
    }

    @Test
    void successWhoseItemsDoNotMatchTheOrderIsDiscardedAndTheOrderStaysReserving() {
        Order order = reservingOrder();
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        StockReservedEvent mismatched = new StockReservedEvent(UUID.randomUUID(), StockReservedEvent.EVENT_TYPE,
                Instant.now(), order.getId(), order.getId().toString(),
                List.of(new ReservationLine(order.getItems().get(0).getProductId(), 99)));

        assertThatThrownBy(() -> service().applyStockReserved(mismatched))
                .isInstanceOf(InvalidSagaMessageException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVING);
        verifyNoInteractions(carrierGateway, orderTimelineEvents, outboxWriter);
    }

    @Test
    void successForAnUnknownOrderIsIgnored() {
        UUID unknownId = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(unknownId)).thenReturn(Optional.empty());
        StockReservedEvent event = new StockReservedEvent(UUID.randomUUID(), StockReservedEvent.EVENT_TYPE,
                Instant.now(), unknownId, unknownId.toString(), List.of(new ReservationLine(UUID.randomUUID(), 1)));

        service().applyStockReserved(event);

        verifyNoInteractions(orderTimelineEvents, outboxWriter, carrierGateway);
    }

    // --- timeout ----------------------------------------------------------------------------

    @Test
    void anOrderReservingSinceBeforeTheCutoffIsCancelledByTimeoutWithReleaseStockAndTheCreationCorrelationId() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = reservingOrder(now.minusMinutes(10));
        order.recordCorrelationId("cid-created");
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        List<String> mdcSeenByTheOutbox = new ArrayList<>();
        doAnswer(call -> {
            mdcSeenByTheOutbox.add(MDC.get("correlationId"));
            return null;
        }).when(outboxWriter).enqueue(any(UUID.class), eq(ReleaseStockCommand.EVENT_TYPE), any(), any());

        service().expireReservation(order.getId(), now.minusMinutes(5));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationCode()).isEqualTo(CancellationCode.RESERVATION_TIMEOUT);
        assertThat(order.getCancellationReason()).isEqualTo(CancellationReasons.forTimeout());
        verify(orderTimelineEvents).cancelled(order);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).enqueue(any(UUID.class), eq(ReleaseStockCommand.EVENT_TYPE),
                eq(order.getId().toString()), payload.capture());
        assertThat(payload.getValue()).isInstanceOfSatisfying(ReleaseStockCommand.class,
                command -> assertThat(command.reason()).isEqualTo(ReleaseStockCommand.RESERVATION_TIMEOUT));
        assertThat(mdcSeenByTheOutbox).containsExactly("cid-created");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void anOrderWithinTheDeadlineOrNoLongerReservingIsLeftUntouchedByTheTimeout() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order fresh = reservingOrder(now.minusSeconds(30));
        Order confirmed = reservingOrder(now.minusMinutes(10));
        confirmed.confirm(now, ASSIGNMENT);
        when(orderRepository.findByIdForUpdate(fresh.getId())).thenReturn(Optional.of(fresh));
        when(orderRepository.findByIdForUpdate(confirmed.getId())).thenReturn(Optional.of(confirmed));

        service().expireReservation(fresh.getId(), now.minusMinutes(5));
        service().expireReservation(confirmed.getId(), now.minusMinutes(5));

        assertThat(fresh.getStatus()).isEqualTo(OrderStatus.RESERVING);
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(outboxWriter, never()).enqueue(any(), any(), any(), any());
        verifyNoInteractions(orderTimelineEvents);
    }

    @Test
    void timeoutForAnUnknownOrderDoesNothing() {
        UUID unknownId = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(unknownId)).thenReturn(Optional.empty());

        service().expireReservation(unknownId, OffsetDateTime.now(ZoneOffset.UTC));

        verifyNoInteractions(outboxWriter, orderTimelineEvents);
    }
}
