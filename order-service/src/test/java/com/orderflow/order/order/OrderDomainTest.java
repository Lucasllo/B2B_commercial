package com.orderflow.order.order;

import com.orderflow.order.credit.CreditPolicy;
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
    void orderStatusHasExactlyTheEightStatesOfOrd10InOrder() {
        assertThat(OrderStatus.values()).containsExactly(
                OrderStatus.CREATED,
                OrderStatus.PENDING_APPROVAL,
                OrderStatus.APPROVED,
                OrderStatus.REJECTED,
                OrderStatus.CONFIRMED,
                OrderStatus.CANCELLED,
                OrderStatus.SHIPPED,
                OrderStatus.DELIVERED);
        assertThat(OrderStatus.CREDIT_CONSUMING).isEqualTo(Set.of(
                OrderStatus.APPROVED, OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.DELIVERED));
    }

    private PricedItem pricedItem() {
        return new PricedItem(1, UUID.randomUUID(), "SKU-1", "Item 1", new BigDecimal("10.00"), 1, new BigDecimal("10.00"));
    }
}
