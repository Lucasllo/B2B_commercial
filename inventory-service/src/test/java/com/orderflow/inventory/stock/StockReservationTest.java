package com.orderflow.inventory.stock;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Livro de reservas, sem Spring e sem Docker (TEST-01, D-66, D-75): a reserva nasce viva e não
 * expedida; a lápide (liberar antes de reservar) nasce já liberada com o instante da liberação; a
 * expedição marca a linha com o instante da baixa física.
 */
class StockReservationTest {

    private static final OffsetDateTime INSTANT = OffsetDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void newReservationIsLiveAndNotShipped() {
        UUID productId = UUID.randomUUID();

        StockReservation reservation = new StockReservation(productId, "order-1", 3);

        assertThat(reservation.getProductId()).isEqualTo(productId);
        assertThat(reservation.getReservationId()).isEqualTo("order-1");
        assertThat(reservation.getQuantity()).isEqualTo(3);
        assertThat(reservation.isReleased()).isFalse();
        assertThat(reservation.getReleasedAt()).isNull();
        assertThat(reservation.isShipped()).isFalse();
        assertThat(reservation.getShippedAt()).isNull();
    }

    @Test
    void tombstoneIsBornReleasedWithTheGivenInstantAndNeverShipped() {
        UUID productId = UUID.randomUUID();

        StockReservation tombstone = StockReservation.tombstone(productId, "order-2", 5, INSTANT);

        assertThat(tombstone.getProductId()).isEqualTo(productId);
        assertThat(tombstone.getReservationId()).isEqualTo("order-2");
        assertThat(tombstone.getQuantity()).isEqualTo(5);
        assertThat(tombstone.isReleased()).isTrue();
        assertThat(tombstone.getReleasedAt()).isEqualTo(INSTANT);
        assertThat(tombstone.isShipped()).isFalse();
    }

    @Test
    void markReleasedFlagsTheRowWithTheInstant() {
        StockReservation reservation = new StockReservation(UUID.randomUUID(), "order-3", 1);

        reservation.markReleased(INSTANT);

        assertThat(reservation.isReleased()).isTrue();
        assertThat(reservation.getReleasedAt()).isEqualTo(INSTANT);
        assertThat(reservation.isShipped()).isFalse();
    }

    @Test
    void markShippedFlagsTheRowWithTheInstantAndDoesNotRelease() {
        StockReservation reservation = new StockReservation(UUID.randomUUID(), "order-4", 2);

        reservation.markShipped(INSTANT);

        assertThat(reservation.isShipped()).isTrue();
        assertThat(reservation.getShippedAt()).isEqualTo(INSTANT);
        assertThat(reservation.isReleased()).isFalse();
    }
}
