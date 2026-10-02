package com.orderflow.order.order;

import com.orderflow.order.credit.CreditPolicy;
import com.orderflow.order.order.exception.InvalidOrderTransitionException;
import com.orderflow.order.order.exception.OrderNotPendingException;
import com.orderflow.order.shipping.CarrierAssignment;
import com.orderflow.order.shipping.TrackingCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes unitários (sem contexto Spring, 04-02 Task 3) da regra de fronteira de crédito
 * ({@link CreditPolicy}) e das transições do agregado {@link Order} — nada aqui faz I/O; a
 * cobertura de ponta a ponta com Postgres real fica em {@code OrderControllerIT}/{@code
 * CreditLockAndExposureIT}.
 */
class OrderDomainTest {

    @Test
    void fitsWithinLimitApprovesAtExactEqualityEvenWithDifferentScales() {
        // (0.00, 100.00, 100.00) — igualdade aprova (D-36).
        assertThat(CreditPolicy.fitsWithinLimit(
                new BigDecimal("0.00"), new BigDecimal("100.00"), new BigDecimal("100.00"))).isTrue();

        // (0.00, 100.01, 100.00) — um centavo acima recusa.
        assertThat(CreditPolicy.fitsWithinLimit(
                new BigDecimal("0.00"), new BigDecimal("100.01"), new BigDecimal("100.00"))).isFalse();

        // (50.0, 50.00, 100.00) — escalas diferentes, mesma quantia (compareTo, nunca equals,
        // 04-RESEARCH.md Common Pitfall 2) — igualdade aprova mesmo assim.
        assertThat(CreditPolicy.fitsWithinLimit(
                new BigDecimal("50.0"), new BigDecimal("50.00"), new BigDecimal("100.00"))).isTrue();

        // (0, 0, 0) — limite zero, pedido zero: igualdade aprova.
        assertThat(CreditPolicy.fitsWithinLimit(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)).isTrue();
    }

    @Test
    void createBuildsOrderInCreatedStatusWithTotalEqualToSumOfSubtotals() {
        UUID companyId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<PricedItem> items = List.of(
                new PricedItem(1, UUID.randomUUID(), "SKU-1", "Item 1", new BigDecimal("10.00"), 2, new BigDecimal("20.00")),
                new PricedItem(2, UUID.randomUUID(), "SKU-2", "Item 2", new BigDecimal("5.00"), 3, new BigDecimal("15.00")));

        Order order = Order.create(companyId, "buyer-1", items, now);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getTotal()).isEqualByComparingTo("35.00");
        assertThat(order.getCompanyId()).isEqualTo(companyId);
        assertThat(order.getItems()).hasSize(2);
    }

    @Test
    void recordCorrelationIdStoresTheCreationIdAndIgnoresALaterValue() {
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), OffsetDateTime.now(ZoneOffset.UTC));

        order.recordCorrelationId("cid-1");
        order.recordCorrelationId("other-id");

        assertThat(order.getCorrelationId()).isEqualTo("cid-1");
    }

    @Test
    void approveAutomaticallyRecordsSystemDeciderReasonAndInstant() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);

        order.approveAutomatically(now);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(order.getDecidedBy()).isEqualTo(Order.SYSTEM_DECIDER);
        assertThat(order.getReason()).isEqualTo(Order.AUTO_APPROVAL_REASON);
        assertThat(order.getDecidedAt()).isEqualTo(now);
    }

    @Test
    void holdForApprovalLeavesTheThreeDecisionFieldsNull() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);

        order.holdForApproval();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_APPROVAL);
        assertThat(order.getDecidedBy()).isNull();
        assertThat(order.getDecidedAt()).isNull();
        assertThat(order.getReason()).isNull();
    }

    @Test
    void transitioningFromAnyStatusOtherThanCreatedThrowsIllegalStateException() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order approved = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        approved.approveAutomatically(now);

        assertThatThrownBy(() -> approved.approveAutomatically(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(approved::holdForApproval).isInstanceOf(IllegalStateException.class);

        Order pending = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        pending.holdForApproval();

        assertThatThrownBy(() -> pending.approveAutomatically(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(pending::holdForApproval).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void orderStatusHasExactlyTheNineStatesInCheckOrder() {
        assertThat(OrderStatus.values()).containsExactly(
                OrderStatus.CREATED,
                OrderStatus.PENDING_APPROVAL,
                OrderStatus.APPROVED,
                OrderStatus.REJECTED,
                OrderStatus.RESERVING,
                OrderStatus.CONFIRMED,
                OrderStatus.CANCELLED,
                OrderStatus.SHIPPED,
                OrderStatus.DELIVERED);
        assertThat(OrderStatus.CREDIT_CONSUMING).isEqualTo(Set.of(
                OrderStatus.APPROVED, OrderStatus.RESERVING, OrderStatus.CONFIRMED,
                OrderStatus.SHIPPED, OrderStatus.DELIVERED));
    }

    @Test
    void startReservationFromApprovedMovesToReservingAndRecordsTheInstantElseThrowsWithoutChangingState() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order approved = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        approved.approveAutomatically(now);

        OffsetDateTime reservationInstant = now.plusSeconds(1);
        approved.startReservation(reservationInstant);
        assertThat(approved.getStatus()).isEqualTo(OrderStatus.RESERVING);
        assertThat(approved.getReservationStartedAt()).isEqualTo(reservationInstant);

        // A partir de CREATED, PENDING_APPROVAL, REJECTED ou já RESERVING — erro de programação,
        // nunca resposta ao cliente, sem alterar status nem reservationStartedAt.
        Order created = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        assertThatThrownBy(() -> created.startReservation(now)).isInstanceOf(IllegalStateException.class);
        assertThat(created.getReservationStartedAt()).isNull();

        Order pending = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        pending.holdForApproval();
        assertThatThrownBy(() -> pending.startReservation(now)).isInstanceOf(IllegalStateException.class);
        assertThat(pending.getReservationStartedAt()).isNull();

        Order rejected = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        rejected.holdForApproval();
        rejected.reject("seller-1", "sem histórico", now);
        assertThatThrownBy(() -> rejected.startReservation(now)).isInstanceOf(IllegalStateException.class);
        assertThat(rejected.getReservationStartedAt()).isNull();

        assertThatThrownBy(() -> approved.startReservation(now)).isInstanceOf(IllegalStateException.class);
        assertThat(approved.getReservationStartedAt()).isEqualTo(reservationInstant);
    }

    @Test
    void approveManuallyAndRejectRecordDecisionFieldsOnlyFromPendingApprovalAndThrowOtherwise() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Order approvedManually = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        approvedManually.holdForApproval();
        approvedManually.approveManually("seller-1", "motivo", now);
        assertThat(approvedManually.getStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(approvedManually.getDecidedBy()).isEqualTo("seller-1");
        assertThat(approvedManually.getDecidedAt()).isEqualTo(now);
        assertThat(approvedManually.getReason()).isEqualTo("motivo");

        Order rejected = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        rejected.holdForApproval();
        rejected.reject("seller-2", "sem histórico", now);
        assertThat(rejected.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.getDecidedBy()).isEqualTo("seller-2");
        assertThat(rejected.getDecidedAt()).isEqualTo(now);
        assertThat(rejected.getReason()).isEqualTo("sem histórico");

        // CREATED — nem approveManually nem reject se aplicam antes de PENDING_APPROVAL.
        Order created = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        assertThatThrownBy(() -> created.approveManually("seller-1", "x", now))
                .isInstanceOf(OrderNotPendingException.class);
        assertThatThrownBy(() -> created.reject("seller-1", "x", now))
                .isInstanceOf(OrderNotPendingException.class);
        assertThat(created.getDecidedBy()).isNull();
        assertThat(created.getDecidedAt()).isNull();
        assertThat(created.getReason()).isNull();

        // APPROVED (automático) — decisão já registrada não é sobrescrita.
        Order approvedAuto = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        approvedAuto.approveAutomatically(now);
        assertThatThrownBy(() -> approvedAuto.approveManually("seller-1", "x", now))
                .isInstanceOf(OrderNotPendingException.class);
        assertThatThrownBy(() -> approvedAuto.reject("seller-1", "x", now))
                .isInstanceOf(OrderNotPendingException.class);
        assertThat(approvedAuto.getDecidedBy()).isEqualTo(Order.SYSTEM_DECIDER);

        // REJECTED — decisão já registrada não é sobrescrita.
        assertThatThrownBy(() -> rejected.approveManually("seller-1", "x", now))
                .isInstanceOf(OrderNotPendingException.class);
        assertThatThrownBy(() -> rejected.reject("seller-1", "x", now))
                .isInstanceOf(OrderNotPendingException.class);
        assertThat(rejected.getDecidedBy()).isEqualTo("seller-2");
    }

    @Test
    void startReservationAfterApproveManuallyPreservesDecidedByDecidedAtAndReason() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        order.holdForApproval();
        order.approveManually("seller-1", "cliente estratégico", now);

        OffsetDateTime reservationInstant = now.plusSeconds(1);
        order.startReservation(reservationInstant);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVING);
        assertThat(order.getReservationStartedAt()).isEqualTo(reservationInstant);
        assertThat(order.getDecidedBy()).isEqualTo("seller-1");
        assertThat(order.getDecidedAt()).isEqualTo(now);
        assertThat(order.getReason()).isEqualTo("cliente estratégico");
    }

    @Test
    void rejectWithNullOrBlankReasonThrowsIllegalArgumentExceptionEvenIfDtoValidationFails() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order pending = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        pending.holdForApproval();

        assertThatThrownBy(() -> pending.reject("seller-1", null, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending.reject("seller-1", "   ", now)).isInstanceOf(IllegalArgumentException.class);
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PENDING_APPROVAL);
        assertThat(pending.getDecidedBy()).isNull();
    }

    @Test
    void cancelFromReservingRecordsCodeReasonAndCancelledAtWithoutTouchingDecisionFields() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        order.approveAutomatically(now);
        OffsetDateTime reservationInstant = now.plusSeconds(1);
        order.startReservation(reservationInstant);

        OffsetDateTime cancelInstant = now.plusSeconds(2);
        order.cancel(CancellationCode.INSUFFICIENT_STOCK, "Estoque insuficiente: produto SKU-1 — disponível 0, solicitado 1",
                cancelInstant);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationCode()).isEqualTo(CancellationCode.INSUFFICIENT_STOCK);
        assertThat(order.getCancellationReason())
                .isEqualTo("Estoque insuficiente: produto SKU-1 — disponível 0, solicitado 1");
        assertThat(order.getCancelledAt()).isEqualTo(cancelInstant);
        assertThat(order.getConfirmedAt()).isNull();
        // decidedBy/decidedAt/reason pertencem à decisão do sistema/vendedor — a saga nunca os toca (D-53).
        assertThat(order.getDecidedBy()).isEqualTo(Order.SYSTEM_DECIDER);
        assertThat(order.getDecidedAt()).isEqualTo(now);
        assertThat(order.getReason()).isEqualTo(Order.AUTO_APPROVAL_REASON);
    }

    @Test
    void cancelFromAnyStatusOtherThanReservingThrowsWithoutChangingState() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Order created = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        assertThatThrownBy(() -> created.cancel(CancellationCode.INSUFFICIENT_STOCK, "x", now))
                .isInstanceOf(IllegalStateException.class);
        assertThat(created.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(created.getCancellationCode()).isNull();

        Order approved = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        approved.approveAutomatically(now);
        assertThatThrownBy(() -> approved.cancel(CancellationCode.INSUFFICIENT_STOCK, "x", now))
                .isInstanceOf(IllegalStateException.class);
        assertThat(approved.getStatus()).isEqualTo(OrderStatus.APPROVED);

        Order reserving = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        reserving.approveAutomatically(now);
        reserving.startReservation(now.plusSeconds(1));
        reserving.cancel(CancellationCode.INSUFFICIENT_STOCK, "x", now.plusSeconds(2));
        assertThatThrownBy(() -> reserving.cancel(CancellationCode.INSUFFICIENT_STOCK, "y", now.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reserving.getCancellationReason()).isEqualTo("x");
    }

    @Test
    void confirmFromReservingRecordsConfirmedAtCarrierAndTrackingCodeElseThrowsWithoutChangingState() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        order.approveAutomatically(now);
        order.startReservation(now.plusSeconds(1));
        CarrierAssignment assignment = assignment();

        OffsetDateTime confirmInstant = now.plusSeconds(2);
        order.confirm(confirmInstant, assignment);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isEqualTo(confirmInstant);
        assertThat(order.getCarrier()).isEqualTo(assignment.carrier());
        assertThat(order.getTrackingCode()).isEqualTo(assignment.trackingCode());
        assertThat(order.getCancellationCode()).isNull();
        assertThat(order.getCancelledAt()).isNull();
        assertThat(order.getShippedAt()).isNull();
        assertThat(order.getShippedBy()).isNull();
        assertThat(order.getDeliveredAt()).isNull();
        assertThat(order.getDeliveredBy()).isNull();

        Order created = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        assertThatThrownBy(() -> created.confirm(now, assignment)).isInstanceOf(IllegalStateException.class);
        assertThat(created.getConfirmedAt()).isNull();
        assertThat(created.getCarrier()).isNull();
        assertThat(created.getTrackingCode()).isNull();

        Order cancelled = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        cancelled.approveAutomatically(now);
        cancelled.startReservation(now.plusSeconds(1));
        cancelled.cancel(CancellationCode.INSUFFICIENT_STOCK, "x", now.plusSeconds(2));
        assertThatThrownBy(() -> cancelled.confirm(now.plusSeconds(3), assignment))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cancelled.getConfirmedAt()).isNull();
        assertThat(cancelled.getCarrier()).isNull();
        assertThat(cancelled.getTrackingCode()).isNull();

        CarrierAssignment other = new CarrierAssignment("Litoral Log", TrackingCodes.fromDigest(new byte[32]));
        assertThatThrownBy(() -> order.confirm(now.plusSeconds(4), other)).isInstanceOf(IllegalStateException.class);
        assertThat(order.getConfirmedAt()).isEqualTo(confirmInstant);
        assertThat(order.getCarrier()).isEqualTo(assignment.carrier());
        assertThat(order.getTrackingCode()).isEqualTo(assignment.trackingCode());
    }

    @Test
    void confirmWithoutAssignmentThrowsIllegalArgumentAndLeavesTheOrderReserving() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        order.approveAutomatically(now);
        order.startReservation(now.plusSeconds(1));

        assertThatThrownBy(() -> order.confirm(now.plusSeconds(2), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVING);
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(order.getCarrier()).isNull();
        assertThat(order.getTrackingCode()).isNull();
    }

    @Test
    void triggerGuardsKeepApproveAutomaticallyAndApproveManuallyApartDespiteSharingTheApprovedTarget() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // PENDING_APPROVAL -> APPROVED é aresta da tabela, mas approveAutomatically é a aresta CREATED -> APPROVED.
        Order pending = orderIn(OrderStatus.PENDING_APPROVAL);
        assertThat(OrderStatus.PENDING_APPROVAL.canTransitionTo(OrderStatus.APPROVED)).isTrue();
        assertThatThrownBy(() -> pending.approveAutomatically(now)).isInstanceOf(IllegalStateException.class);
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PENDING_APPROVAL);

        // CREATED -> APPROVED é aresta da tabela, mas approveManually é a aresta PENDING_APPROVAL -> APPROVED.
        Order created = orderIn(OrderStatus.CREATED);
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.APPROVED)).isTrue();
        assertThatThrownBy(() -> created.approveManually("seller-1", "x", now))
                .isInstanceOf(OrderNotPendingException.class);
        assertThat(created.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(created.getDecidedBy()).isNull();
    }

    @Test
    void everyTransitionMethodCalledFromAStatusTheTableForbidsThrowsTheExpectedExceptionAndChangesNothing() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CarrierAssignment assignment = assignment();
        OrderStatus[] reachable = {
                OrderStatus.CREATED, OrderStatus.PENDING_APPROVAL, OrderStatus.APPROVED, OrderStatus.REJECTED,
                OrderStatus.RESERVING, OrderStatus.CONFIRMED, OrderStatus.CANCELLED};

        for (OrderStatus from : reachable) {
            if (from != OrderStatus.CREATED) {
                assertRejected(from, "holdForApproval", IllegalStateException.class, Order::holdForApproval);
                assertRejected(from, "approveAutomatically", IllegalStateException.class,
                        o -> o.approveAutomatically(now));
            }
            if (from != OrderStatus.PENDING_APPROVAL) {
                assertRejected(from, "approveManually", OrderNotPendingException.class,
                        o -> o.approveManually("seller-1", "x", now));
                assertRejected(from, "reject", OrderNotPendingException.class,
                        o -> o.reject("seller-1", "x", now));
            }
            if (from != OrderStatus.APPROVED) {
                assertRejected(from, "startReservation", IllegalStateException.class, o -> o.startReservation(now));
            }
            if (from != OrderStatus.RESERVING) {
                assertRejected(from, "confirm", IllegalStateException.class, o -> o.confirm(now, assignment));
                assertRejected(from, "cancel", IllegalStateException.class,
                        o -> o.cancel(CancellationCode.INSUFFICIENT_STOCK, "x", now));
            }
        }
    }

    @Test
    void shipFromConfirmedRecordsShippedAtAndShippedByAndKeepsTheFulfillmentFieldsUntouched() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = orderIn(OrderStatus.CONFIRMED);
        String carrier = order.getCarrier();
        String trackingCode = order.getTrackingCode();
        OffsetDateTime confirmedAt = order.getConfirmedAt();

        OffsetDateTime shipInstant = now.plusSeconds(10);
        order.ship("seller-7", shipInstant);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getShippedAt()).isEqualTo(shipInstant);
        assertThat(order.getShippedBy()).isEqualTo("seller-7");
        assertThat(order.getCarrier()).isEqualTo(carrier);
        assertThat(order.getTrackingCode()).isEqualTo(trackingCode);
        assertThat(order.getConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(order.getDeliveredAt()).isNull();
        assertThat(order.getDeliveredBy()).isNull();
    }

    @Test
    void shipFromAnyStatusOtherThanConfirmedThrowsInvalidTransitionWithFromAndToAndChangesNothing() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        for (OrderStatus from : OrderStatus.values()) {
            if (from == OrderStatus.CONFIRMED) {
                continue;
            }
            Order order = orderIn(from);
            List<Object> before = snapshot(order);

            assertThatThrownBy(() -> order.ship("seller-7", now))
                    .as("ship from %s", from)
                    .isInstanceOfSatisfying(InvalidOrderTransitionException.class, ex -> {
                        assertThat(ex.getFrom()).isEqualTo(from);
                        assertThat(ex.getTo()).isEqualTo(OrderStatus.SHIPPED);
                        assertThat(ex.getMessage())
                                .isEqualTo("Order cannot transition from " + from + " to SHIPPED");
                    });

            assertThat(snapshot(order)).as("ship from %s must not change anything", from).isEqualTo(before);
        }
    }

    @Test
    void shipWithNullOrBlankShippedByThrowsIllegalArgumentExceptionAndLeavesTheOrderConfirmed() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = orderIn(OrderStatus.CONFIRMED);

        assertThatThrownBy(() -> order.ship(null, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.ship("   ", now)).isInstanceOf(IllegalArgumentException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getShippedAt()).isNull();
        assertThat(order.getShippedBy()).isNull();
    }

    @Test
    void deliverFromShippedRecordsDeliveredAtAndDeliveredByAndKeepsTheShipmentFieldsUntouched() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = orderIn(OrderStatus.SHIPPED);
        OffsetDateTime shippedAt = order.getShippedAt();
        String shippedBy = order.getShippedBy();

        OffsetDateTime deliverInstant = now.plusSeconds(20);
        order.deliver("seller-8", deliverInstant);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getDeliveredAt()).isEqualTo(deliverInstant);
        assertThat(order.getDeliveredBy()).isEqualTo("seller-8");
        assertThat(order.getShippedAt()).isEqualTo(shippedAt);
        assertThat(order.getShippedBy()).isEqualTo(shippedBy);
    }

    @Test
    void deliverFromAnyStatusOtherThanShippedThrowsInvalidTransitionWithFromAndToAndChangesNothing() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        for (OrderStatus from : OrderStatus.values()) {
            if (from == OrderStatus.SHIPPED) {
                continue;
            }
            Order order = orderIn(from);
            List<Object> before = snapshot(order);

            assertThatThrownBy(() -> order.deliver("seller-8", now))
                    .as("deliver from %s", from)
                    .isInstanceOfSatisfying(InvalidOrderTransitionException.class, ex -> {
                        assertThat(ex.getFrom()).isEqualTo(from);
                        assertThat(ex.getTo()).isEqualTo(OrderStatus.DELIVERED);
                        assertThat(ex.getMessage())
                                .isEqualTo("Order cannot transition from " + from + " to DELIVERED");
                    });

            assertThat(snapshot(order)).as("deliver from %s must not change anything", from).isEqualTo(before);
        }
    }

    @Test
    void deliverWithNullOrBlankDeliveredByThrowsIllegalArgumentExceptionAndLeavesTheOrderShipped() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = orderIn(OrderStatus.SHIPPED);

        assertThatThrownBy(() -> order.deliver(null, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.deliver("  ", now)).isInstanceOf(IllegalArgumentException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getDeliveredAt()).isNull();
        assertThat(order.getDeliveredBy()).isNull();
    }

    private void assertRejected(OrderStatus from, String method, Class<? extends RuntimeException> expected,
                                java.util.function.Consumer<Order> call) {
        Order order = orderIn(from);
        List<Object> before = snapshot(order);

        assertThatThrownBy(() -> call.accept(order))
                .as("%s from %s", method, from)
                .isInstanceOf(expected);

        assertThat(snapshot(order)).as("%s from %s must not change anything", method, from).isEqualTo(before);
    }

    private List<Object> snapshot(Order order) {
        return java.util.Arrays.asList(order.getStatus(), order.getDecidedBy(), order.getDecidedAt(),
                order.getReason(), order.getReservationStartedAt(), order.getConfirmedAt(), order.getCancelledAt(),
                order.getCancellationCode(), order.getCancellationReason(), order.getCarrier(),
                order.getTrackingCode(), order.getShippedAt(), order.getShippedBy(), order.getDeliveredAt(),
                order.getDeliveredBy());
    }

    private Order orderIn(OrderStatus target) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(pricedItem()), now);
        switch (target) {
            case CREATED -> { }
            case PENDING_APPROVAL -> order.holdForApproval();
            case APPROVED -> order.approveAutomatically(now);
            case REJECTED -> {
                order.holdForApproval();
                order.reject("seller-1", "motivo", now);
            }
            case RESERVING -> {
                order.approveAutomatically(now);
                order.startReservation(now.plusSeconds(1));
            }
            case CONFIRMED -> {
                order.approveAutomatically(now);
                order.startReservation(now.plusSeconds(1));
                order.confirm(now.plusSeconds(2), assignment());
            }
            case CANCELLED -> {
                order.approveAutomatically(now);
                order.startReservation(now.plusSeconds(1));
                order.cancel(CancellationCode.INSUFFICIENT_STOCK, "x", now.plusSeconds(2));
            }
            case SHIPPED -> {
                order.approveAutomatically(now);
                order.startReservation(now.plusSeconds(1));
                order.confirm(now.plusSeconds(2), assignment());
                order.ship("seller-1", now.plusSeconds(3));
            }
            case DELIVERED -> {
                order.approveAutomatically(now);
                order.startReservation(now.plusSeconds(1));
                order.confirm(now.plusSeconds(2), assignment());
                order.ship("seller-1", now.plusSeconds(3));
                order.deliver("seller-1", now.plusSeconds(4));
            }
        }
        return order;
    }

    private CarrierAssignment assignment() {
        return new CarrierAssignment("Norte Entregas", TrackingCodes.fromDigest(new byte[32]));
    }

    private PricedItem pricedItem() {
        return new PricedItem(1, UUID.randomUUID(), "SKU-1", "Item 1", new BigDecimal("10.00"), 1, new BigDecimal("10.00"));
    }
}
