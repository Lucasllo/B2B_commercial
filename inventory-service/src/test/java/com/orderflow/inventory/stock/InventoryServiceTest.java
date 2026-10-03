package com.orderflow.inventory.stock;

import com.orderflow.inventory.saga.messaging.dto.ReservationFailureLine;
import com.orderflow.inventory.saga.messaging.dto.ReservationLine;
import com.orderflow.inventory.saga.messaging.dto.StockReservationFailedEvent;
import com.orderflow.inventory.saga.messaging.dto.StockReservedEvent;
import com.orderflow.inventory.saga.outbox.OutboxWriter;
import com.orderflow.inventory.stock.dto.StockAdjustedEvent;
import com.orderflow.inventory.stock.dto.StockResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Orquestração das regras de estoque, sem Spring e sem Docker (TEST-01): reserva atômica que nunca
 * excede o disponível (INV-02, D-10), idempotência por reservationId (D-11, D-65), liberação
 * idempotente e lápide (D-14, D-66), reserva multi-item tudo-ou-nada com o código de falha certo
 * (D-55, D-56, D-58), baixa física pela quantidade do livro (D-75) e ajuste de estoque nunca abaixo do
 * reservado (D-18). {@code @Retryable} e {@code @Transactional} são inertes aqui — as tentativas e o
 * commit são provados pelos ITs com Postgres real.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final String RESERVATION_ID = "order-1";

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private StockReservationRepository stockReservationRepository;

    @Mock
    private OutboxWriter outboxWriter;

    private InventoryService service() {
        return new InventoryService(inventoryRepository, stockReservationRepository, outboxWriter);
    }

    private static Inventory inventoryWith(UUID productId, int onHand, int reserved) {
        Inventory inventory = new Inventory(productId, onHand);
        if (reserved > 0) {
            inventory.reserve(reserved);
        }
        return inventory;
    }

    // --- reserva simples (REST) ---------------------------------------------------------------

    @Test
    void reserveWithinAvailableWritesTheLedgerRowAndRaisesReserved() {
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWith(productId, 10, 2);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(stockReservationRepository.findByProductIdAndReservationId(productId, RESERVATION_ID))
                .thenReturn(Optional.empty());

        StockResponse response = service().reserve(productId, RESERVATION_ID, 8);

        assertThat(response.quantityReserved()).isEqualTo(10);
        assertThat(response.quantityAvailable()).isZero();
        ArgumentCaptor<StockReservation> saved = ArgumentCaptor.forClass(StockReservation.class);
        verify(stockReservationRepository).save(saved.capture());
        assertThat(saved.getValue().getReservationId()).isEqualTo(RESERVATION_ID);
        assertThat(saved.getValue().getQuantity()).isEqualTo(8);
        verify(inventoryRepository).saveAndFlush(inventory);
    }

    @Test
    void reserveBeyondAvailableThrowsWithAvailableAndRequestedAndChangesNothing() {
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWith(productId, 10, 4);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(stockReservationRepository.findByProductIdAndReservationId(productId, RESERVATION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().reserve(productId, RESERVATION_ID, 7))
                .isInstanceOfSatisfying(InsufficientStockException.class, ex -> {
                    assertThat(ex.getAvailable()).isEqualTo(6);
                    assertThat(ex.getRequested()).isEqualTo(7);
                });

        assertThat(inventory.getQuantityReserved()).isEqualTo(4);
        verify(stockReservationRepository, never()).save(any());
        verify(inventoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void reserveForAProductWithoutInventoryLineThrowsNotFound() {
        UUID productId = UUID.randomUUID();
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().reserve(productId, RESERVATION_ID, 1))
                .isInstanceOf(InventoryNotFoundException.class);

        verifyNoInteractions(stockReservationRepository);
    }

    @Test
    void reserveWithAnAlreadyUsedReservationIdIsAnIdempotentNoOp() {
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWith(productId, 10, 3);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(stockReservationRepository.findByProductIdAndReservationId(productId, RESERVATION_ID))
                .thenReturn(Optional.of(new StockReservation(productId, RESERVATION_ID, 3)));

        StockResponse response = service().reserve(productId, RESERVATION_ID, 3);

        assertThat(response.quantityReserved()).isEqualTo(3);
        verify(stockReservationRepository, never()).save(any());
        verify(inventoryRepository, never()).saveAndFlush(any());
    }

    // --- liberação simples (REST) -------------------------------------------------------------

    @Test
    void releaseOfALiveReservationGivesTheBookQuantityBackAndMarksTheRowReleased() {
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWith(productId, 10, 5);
        StockReservation reservation = new StockReservation(productId, RESERVATION_ID, 3);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(stockReservationRepository.findByProductIdAndReservationId(productId, RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        StockResponse response = service().release(productId, RESERVATION_ID);

        assertThat(response.quantityReserved()).isEqualTo(2);
        assertThat(reservation.isReleased()).isTrue();
        verify(inventoryRepository).saveAndFlush(inventory);
    }

    @Test
    void releaseOfAnAbsentOrAlreadyReleasedOrAlreadyShippedReservationIsANoOp() {
        UUID absent = UUID.randomUUID();
        UUID released = UUID.randomUUID();
        UUID shipped = UUID.randomUUID();
        Inventory absentInventory = inventoryWith(absent, 10, 5);
        Inventory releasedInventory = inventoryWith(released, 10, 5);
        Inventory shippedInventory = inventoryWith(shipped, 10, 5);
        StockReservation releasedRow = new StockReservation(released, RESERVATION_ID, 3);
        releasedRow.markReleased(OffsetDateTime.now(ZoneOffset.UTC));
        StockReservation shippedRow = new StockReservation(shipped, RESERVATION_ID, 3);
        shippedRow.markShipped(OffsetDateTime.now(ZoneOffset.UTC));
        when(inventoryRepository.findByProductId(absent)).thenReturn(Optional.of(absentInventory));
        when(inventoryRepository.findByProductId(released)).thenReturn(Optional.of(releasedInventory));
        when(inventoryRepository.findByProductId(shipped)).thenReturn(Optional.of(shippedInventory));
        when(stockReservationRepository.findByProductIdAndReservationId(absent, RESERVATION_ID))
                .thenReturn(Optional.empty());
        when(stockReservationRepository.findByProductIdAndReservationId(released, RESERVATION_ID))
                .thenReturn(Optional.of(releasedRow));
        when(stockReservationRepository.findByProductIdAndReservationId(shipped, RESERVATION_ID))
                .thenReturn(Optional.of(shippedRow));

        service().release(absent, RESERVATION_ID);
        service().release(released, RESERVATION_ID);
        service().release(shipped, RESERVATION_ID);

        assertThat(absentInventory.getQuantityReserved()).isEqualTo(5);
        assertThat(releasedInventory.getQuantityReserved()).isEqualTo(5);
        assertThat(shippedInventory.getQuantityReserved()).isEqualTo(5);
        verify(inventoryRepository, never()).saveAndFlush(any());
    }

    // --- reserva multi-item (saga) ------------------------------------------------------------

    @Test
    void reserveAllWithStockForEveryLineReservesAllWritesOneRowPerLineAndEmitsStockReserved() {
        UUID orderId = UUID.randomUUID();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        Inventory inventoryA = inventoryWith(productA, 10, 0);
        Inventory inventoryB = inventoryWith(productB, 5, 1);
        when(stockReservationRepository.findByReservationIdAndProductIdIn(eq(RESERVATION_ID), anyCollection()))
                .thenReturn(List.of());
        when(inventoryRepository.findByProductIdIn(anyCollection())).thenReturn(List.of(inventoryA, inventoryB));

        ReservationOutcome outcome = service().reserveAll(orderId, RESERVATION_ID,
                List.of(new ReservationLine(productA, 4), new ReservationLine(productB, 4)));

        assertThat(outcome.reserved()).isTrue();
        assertThat(outcome.failures()).isEmpty();
        assertThat(inventoryA.getQuantityReserved()).isEqualTo(4);
        assertThat(inventoryB.getQuantityReserved()).isEqualTo(5);
        verify(stockReservationRepository, times(2)).save(any(StockReservation.class));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).enqueue(any(UUID.class), eq(StockReservedEvent.EVENT_TYPE), eq(orderId.toString()),
                payload.capture());
        assertThat(payload.getValue()).isInstanceOf(StockReservedEvent.class);
    }

    @Test
    void reserveAllWithOneInsufficientLineReservesNothingAndFailsWithTheAvailableQuantity() {
        UUID orderId = UUID.randomUUID();
        UUID enough = UUID.randomUUID();
        UUID scarce = UUID.randomUUID();
        Inventory enoughInventory = inventoryWith(enough, 10, 0);
        Inventory scarceInventory = inventoryWith(scarce, 3, 1);
        when(stockReservationRepository.findByReservationIdAndProductIdIn(eq(RESERVATION_ID), anyCollection()))
                .thenReturn(List.of());
        when(inventoryRepository.findByProductIdIn(anyCollection()))
                .thenReturn(List.of(enoughInventory, scarceInventory));

        ReservationOutcome outcome = service().reserveAll(orderId, RESERVATION_ID,
                List.of(new ReservationLine(enough, 2), new ReservationLine(scarce, 5)));

        assertThat(outcome.reserved()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo(StockReservationFailedEvent.INSUFFICIENT_STOCK);
        assertThat(outcome.failures()).containsExactly(new ReservationFailureLine(scarce, 5, 2));
        assertThat(enoughInventory.getQuantityReserved()).isZero();
        assertThat(scarceInventory.getQuantityReserved()).isEqualTo(1);
        verify(stockReservationRepository, never()).save(any());
        verify(inventoryRepository, never()).saveAndFlush(any());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).enqueue(any(UUID.class), eq(StockReservationFailedEvent.EVENT_TYPE),
                eq(orderId.toString()), payload.capture());
        assertThat(payload.getValue()).isInstanceOfSatisfying(StockReservationFailedEvent.class,
                event -> assertThat(event.reasonCode()).isEqualTo(StockReservationFailedEvent.INSUFFICIENT_STOCK));
    }

    @Test
    void reserveAllWithAProductWithoutAStockLineFailsAsProductNotStockedListingEveryFailure() {
        UUID orderId = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID scarce = UUID.randomUUID();
        Inventory scarceInventory = inventoryWith(scarce, 1, 0);
        when(stockReservationRepository.findByReservationIdAndProductIdIn(eq(RESERVATION_ID), anyCollection()))
                .thenReturn(List.of());
        when(inventoryRepository.findByProductIdIn(anyCollection())).thenReturn(List.of(scarceInventory));

        ReservationOutcome outcome = service().reserveAll(orderId, RESERVATION_ID,
                List.of(new ReservationLine(missing, 2), new ReservationLine(scarce, 3)));

        assertThat(outcome.reserved()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo(StockReservationFailedEvent.PRODUCT_NOT_STOCKED);
        assertThat(outcome.failures()).hasSize(2)
                .contains(new ReservationFailureLine(missing, 2, 0), new ReservationFailureLine(scarce, 3, 1));
        verify(stockReservationRepository, never()).save(any());
    }

    @Test
    void reserveAllReplayOverAnExistingLedgerReemitsStockReservedWithoutTouchingStock() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWith(productId, 10, 4);
        when(stockReservationRepository.findByReservationIdAndProductIdIn(eq(RESERVATION_ID), anyCollection()))
                .thenReturn(List.of(new StockReservation(productId, RESERVATION_ID, 4)));

        ReservationOutcome outcome = service().reserveAll(orderId, RESERVATION_ID,
                List.of(new ReservationLine(productId, 4)));

        assertThat(outcome.reserved()).isTrue();
        assertThat(inventory.getQuantityReserved()).isEqualTo(4);
        verifyNoInteractions(inventoryRepository);
        verify(stockReservationRepository, never()).save(any());
        verify(outboxWriter).enqueue(any(UUID.class), eq(StockReservedEvent.EVENT_TYPE), eq(orderId.toString()),
                any(StockReservedEvent.class));
    }

    @Test
    void reserveAllFindingATombstoneFailsAsReservationCancelledAndReservesNothing() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        StockReservation tombstone = StockReservation.tombstone(productId, RESERVATION_ID, 4,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(stockReservationRepository.findByReservationIdAndProductIdIn(eq(RESERVATION_ID), anyCollection()))
                .thenReturn(List.of(tombstone));

        ReservationOutcome outcome = service().reserveAll(orderId, RESERVATION_ID,
                List.of(new ReservationLine(productId, 4)));

        assertThat(outcome.reserved()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo(StockReservationFailedEvent.RESERVATION_CANCELLED);
        assertThat(outcome.failures()).isEmpty();
        verifyNoInteractions(inventoryRepository);
        verify(stockReservationRepository, never()).save(any());
    }

    @Test
    void reserveAllOverAPartiallyFilledLedgerIsAnInconsistencyAndEmitsNothing() {
        UUID orderId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(stockReservationRepository.findByReservationIdAndProductIdIn(eq(RESERVATION_ID), anyCollection()))
                .thenReturn(List.of(new StockReservation(first, RESERVATION_ID, 1)));

        assertThatThrownBy(() -> service().reserveAll(orderId, RESERVATION_ID,
                List.of(new ReservationLine(first, 1), new ReservationLine(second, 1))))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(outboxWriter);
    }

    // --- liberação multi-item (saga) ----------------------------------------------------------

    @Test
    void releaseAllWithoutALedgerRowWritesATombstoneForTheLine() {
        UUID productId = UUID.randomUUID();
        when(stockReservationRepository.findByProductIdAndReservationId(productId, RESERVATION_ID))
                .thenReturn(Optional.empty());

        service().releaseAll(UUID.randomUUID(), RESERVATION_ID, List.of(new ReservationLine(productId, 2)));

        ArgumentCaptor<StockReservation> saved = ArgumentCaptor.forClass(StockReservation.class);
        verify(stockReservationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isReleased()).isTrue();
        assertThat(saved.getValue().getQuantity()).isEqualTo(2);
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void releaseAllOfALiveRowGivesTheBookQuantityBackAndAReleasedOrShippedRowIsLeftAlone() {
        UUID live = UUID.randomUUID();
        UUID released = UUID.randomUUID();
        UUID shipped = UUID.randomUUID();
        Inventory liveInventory = inventoryWith(live, 10, 6);
        StockReservation liveRow = new StockReservation(live, RESERVATION_ID, 4);
        StockReservation releasedRow = new StockReservation(released, RESERVATION_ID, 4);
        releasedRow.markReleased(OffsetDateTime.now(ZoneOffset.UTC));
        StockReservation shippedRow = new StockReservation(shipped, RESERVATION_ID, 4);
        shippedRow.markShipped(OffsetDateTime.now(ZoneOffset.UTC));
        when(stockReservationRepository.findByProductIdAndReservationId(live, RESERVATION_ID))
                .thenReturn(Optional.of(liveRow));
        when(stockReservationRepository.findByProductIdAndReservationId(released, RESERVATION_ID))
                .thenReturn(Optional.of(releasedRow));
        when(stockReservationRepository.findByProductIdAndReservationId(shipped, RESERVATION_ID))
                .thenReturn(Optional.of(shippedRow));
        when(inventoryRepository.findByProductId(live)).thenReturn(Optional.of(liveInventory));

        service().releaseAll(UUID.randomUUID(), RESERVATION_ID, List.of(
                new ReservationLine(live, 4), new ReservationLine(released, 4), new ReservationLine(shipped, 4)));

        assertThat(liveInventory.getQuantityReserved()).isEqualTo(2);
        assertThat(liveRow.isReleased()).isTrue();
        verify(inventoryRepository, times(1)).saveAndFlush(any(Inventory.class));
        verify(inventoryRepository, never()).findByProductId(released);
        verify(inventoryRepository, never()).findByProductId(shipped);
    }

    // --- baixa física (saga) ------------------------------------------------------------------

    @Test
    void shipAllDecrementsByTheBookQuantityNotTheCommandOneAndMarksTheRowShipped() {
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWith(productId, 10, 3);
        StockReservation row = new StockReservation(productId, RESERVATION_ID, 3);
        when(stockReservationRepository.findByProductIdAndReservationId(productId, RESERVATION_ID))
                .thenReturn(Optional.of(row));
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));

        service().shipAll(UUID.randomUUID(), RESERVATION_ID, List.of(new ReservationLine(productId, 99)));

        assertThat(inventory.getQuantityOnHand()).isEqualTo(7);
        assertThat(inventory.getQuantityReserved()).isZero();
        assertThat(row.isShipped()).isTrue();
        verify(inventoryRepository).saveAndFlush(inventory);
        verify(stockReservationRepository).saveAndFlush(row);
    }

    @Test
    void shipAllOfAnAlreadyShippedRowIsIgnoredWithoutDecrementingAgain() {
        UUID productId = UUID.randomUUID();
        StockReservation row = new StockReservation(productId, RESERVATION_ID, 3);
        row.markShipped(OffsetDateTime.now(ZoneOffset.UTC));
        when(stockReservationRepository.findByProductIdAndReservationId(productId, RESERVATION_ID))
                .thenReturn(Optional.of(row));

        service().shipAll(UUID.randomUUID(), RESERVATION_ID, List.of(new ReservationLine(productId, 3)));

        verifyNoInteractions(inventoryRepository);
        verify(stockReservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void shipAllWithoutALedgerRowIsAnAnomalyAndChangesNothing() {
        UUID productId = UUID.randomUUID();
        when(stockReservationRepository.findByProductIdAndReservationId(productId, RESERVATION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().shipAll(UUID.randomUUID(), RESERVATION_ID,
                List.of(new ReservationLine(productId, 3)))).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void shipAllOfAReleasedReservationIsAnAnomalyAndChangesNothing() {
        UUID productId = UUID.randomUUID();
        StockReservation row = new StockReservation(productId, RESERVATION_ID, 3);
        row.markReleased(OffsetDateTime.now(ZoneOffset.UTC));
        when(stockReservationRepository.findByProductIdAndReservationId(productId, RESERVATION_ID))
                .thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service().shipAll(UUID.randomUUID(), RESERVATION_ID,
                List.of(new ReservationLine(productId, 3)))).isInstanceOf(IllegalStateException.class);

        assertThat(row.isShipped()).isFalse();
        verifyNoInteractions(inventoryRepository);
    }

    // --- ajuste de estoque (REST) -------------------------------------------------------------

    @Test
    void setStockForANewProductCreatesTheLineAndEmitsStockAdjustedFromZero() {
        UUID productId = UUID.randomUUID();
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.empty());
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(call -> call.getArgument(0));

        StockResponse response = service().setStock(productId, 12);

        assertThat(response.quantityOnHand()).isEqualTo(12);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).enqueue(any(UUID.class), eq(StockAdjustedEvent.EVENT_TYPE), eq(productId.toString()),
                payload.capture());
        assertThat(payload.getValue()).isInstanceOfSatisfying(StockAdjustedEvent.class, event -> {
            assertThat(event.previousQuantityOnHand()).isZero();
            assertThat(event.newQuantityOnHand()).isEqualTo(12);
        });
    }

    @Test
    void setStockForAnExistingProductUpdatesTheSameLineAndEmitsTheEventWithThePreviousQuantity() {
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWith(productId, 10, 3);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));

        StockResponse response = service().setStock(productId, 20);

        assertThat(response.quantityOnHand()).isEqualTo(20);
        assertThat(response.quantityReserved()).isEqualTo(3);
        verify(inventoryRepository, never()).save(any());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).enqueue(any(UUID.class), eq(StockAdjustedEvent.EVENT_TYPE), eq(productId.toString()),
                payload.capture());
        assertThat(payload.getValue()).isInstanceOfSatisfying(StockAdjustedEvent.class, event -> {
            assertThat(event.previousQuantityOnHand()).isEqualTo(10);
            assertThat(event.newQuantityOnHand()).isEqualTo(20);
        });
    }

    @Test
    void setStockBelowTheReservedQuantityIsRefusedWithoutChangingOrPublishingAnything() {
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWith(productId, 10, 6);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> service().setStock(productId, 5))
                .isInstanceOfSatisfying(StockBelowReservedException.class, ex -> {
                    assertThat(ex.getReserved()).isEqualTo(6);
                    assertThat(ex.getRequestedOnHand()).isEqualTo(5);
                });

        assertThat(inventory.getQuantityOnHand()).isEqualTo(10);
        verifyNoInteractions(outboxWriter);
    }
}
