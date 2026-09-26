package com.orderflow.order.saga;

import com.orderflow.order.order.Order;
import com.orderflow.order.order.PricedItem;
import com.orderflow.order.saga.messaging.dto.ReservationFailureLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unitário puro (sem contexto Spring) — {@link CancellationReasons} monta o texto legível de
 * {@code cancellation_reason} (D-56) a partir de um modelo fixo, nunca texto livre.
 */
class CancellationReasonsTest {

    private Order orderWithItem(UUID productId, String sku) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PricedItem item = new PricedItem(1, productId, sku, "Item", new BigDecimal("10.00"), 1, new BigDecimal("10.00"));
        return Order.create(UUID.randomUUID(), "buyer-1", List.of(item), now);
    }

    @Test
    void insufficientStockUsesSkuFromOrderItemAndAvailableRequestedNumbers() {
        UUID productId = UUID.randomUUID();
        Order order = orderWithItem(productId, "SKU-XYZ");
        List<ReservationFailureLine> failures = List.of(new ReservationFailureLine(productId, 5, 2));

        String reason = CancellationReasons.forFailure("INSUFFICIENT_STOCK", failures, order);

        assertThat(reason).isEqualTo("Estoque insuficiente: produto SKU-XYZ — disponível 2, solicitado 5");
    }

    @Test
    void productNotStockedHasItsOwnPrefix() {
        UUID productId = UUID.randomUUID();
        Order order = orderWithItem(productId, "SKU-ABC");
        List<ReservationFailureLine> failures = List.of(new ReservationFailureLine(productId, 3, 0));

        String reason = CancellationReasons.forFailure("PRODUCT_NOT_STOCKED", failures, order);

        assertThat(reason).isEqualTo("Produto sem estoque cadastrado: produto SKU-ABC — disponível 0, solicitado 3");
    }

    @Test
    void reservationCancelledHasFixedTextRegardlessOfFailures() {
        Order order = orderWithItem(UUID.randomUUID(), "SKU-ANY");

        String reason = CancellationReasons.forFailure("RESERVATION_CANCELLED", List.of(), order);

        assertThat(reason).isEqualTo("Reserva de estoque cancelada antes de ser processada pelo estoque");
    }

    @Test
    void productIdNotFoundInOrderItemsFallsBackToRawProductId() {
        UUID productId = UUID.randomUUID();
        UUID unknownProductId = UUID.randomUUID();
        Order order = orderWithItem(productId, "SKU-XYZ");
        List<ReservationFailureLine> failures = List.of(new ReservationFailureLine(unknownProductId, 1, 0));

        String reason = CancellationReasons.forFailure("INSUFFICIENT_STOCK", failures, order);

        assertThat(reason).contains("produto " + unknownProductId);
    }

    @Test
    void multipleFailuresAreJoinedBySemicolon() {
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PricedItem item1 = new PricedItem(1, productId1, "SKU-1", "Item 1", new BigDecimal("10.00"), 1, new BigDecimal("10.00"));
        PricedItem item2 = new PricedItem(2, productId2, "SKU-2", "Item 2", new BigDecimal("5.00"), 1, new BigDecimal("5.00"));
        Order order = Order.create(UUID.randomUUID(), "buyer-1", List.of(item1, item2), now);
        List<ReservationFailureLine> failures = List.of(
                new ReservationFailureLine(productId1, 5, 2),
                new ReservationFailureLine(productId2, 3, 0));

        String reason = CancellationReasons.forFailure("INSUFFICIENT_STOCK", failures, order);

        assertThat(reason).isEqualTo("Estoque insuficiente: produto SKU-1 — disponível 2, solicitado 5; "
                + "produto SKU-2 — disponível 0, solicitado 3");
    }

    @Test
    void textLongerThan500CharsIsTruncatedWithEllipsis() {
        UUID productId = UUID.randomUUID();
        Order order = orderWithItem(productId, "SKU-" + "X".repeat(600));
        List<ReservationFailureLine> failures = List.of(new ReservationFailureLine(productId, 5, 2));

        String reason = CancellationReasons.forFailure("INSUFFICIENT_STOCK", failures, order);

        assertThat(reason).hasSize(500);
        assertThat(reason).endsWith("…");
    }
}
