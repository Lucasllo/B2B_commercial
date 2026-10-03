package com.orderflow.inventory.stock;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regras de quantidade do estoque, sem Spring e sem Docker (TEST-01, D-08, D-57, D-75): o disponível é
 * sempre físico menos reservado; liberar nunca deixa o reservado negativo; a baixa física só acontece
 * dentro do que está reservado e do que existe fisicamente, e uma baixa recusada não altera nenhuma
 * quantidade.
 */
class InventoryTest {

    private static Inventory inventoryWithOnHand(int onHand) {
        return new Inventory(UUID.randomUUID(), onHand);
    }

    @Test
    void newInventoryHasEverythingAvailableAndNothingReserved() {
        Inventory inventory = inventoryWithOnHand(10);

        assertThat(inventory.getQuantityOnHand()).isEqualTo(10);
        assertThat(inventory.getQuantityReserved()).isZero();
        assertThat(inventory.availableQuantity()).isEqualTo(10);
    }

    @Test
    void reserveAddsToReservedAndAvailableIsOnHandMinusReserved() {
        Inventory inventory = inventoryWithOnHand(10);

        inventory.reserve(3);

        assertThat(inventory.getQuantityReserved()).isEqualTo(3);
        assertThat(inventory.getQuantityOnHand()).isEqualTo(10);
        assertThat(inventory.availableQuantity()).isEqualTo(7);
    }

    @Test
    void releaseSubtractsFromReservedAndGivesTheQuantityBackToAvailable() {
        Inventory inventory = inventoryWithOnHand(10);
        inventory.reserve(5);

        inventory.release(2);

        assertThat(inventory.getQuantityReserved()).isEqualTo(3);
        assertThat(inventory.availableQuantity()).isEqualTo(7);
    }

    @Test
    void releaseNeverLeavesReservedNegative() {
        Inventory inventory = inventoryWithOnHand(10);
        inventory.reserve(3);

        inventory.release(5);

        assertThat(inventory.getQuantityReserved()).isZero();
        assertThat(inventory.availableQuantity()).isEqualTo(10);
    }

    @Test
    void shipDecrementsBothOnHandAndReserved() {
        Inventory inventory = inventoryWithOnHand(10);
        inventory.reserve(3);

        inventory.ship(2);

        assertThat(inventory.getQuantityOnHand()).isEqualTo(8);
        assertThat(inventory.getQuantityReserved()).isEqualTo(1);
        assertThat(inventory.availableQuantity()).isEqualTo(7);
    }

    @Test
    void shipOfTheWholeReservationLeavesAvailableUnchangedForOtherOrders() {
        Inventory inventory = inventoryWithOnHand(10);
        inventory.reserve(3);
        int availableBefore = inventory.availableQuantity();

        inventory.ship(3);

        assertThat(inventory.getQuantityOnHand()).isEqualTo(7);
        assertThat(inventory.getQuantityReserved()).isZero();
        assertThat(inventory.availableQuantity()).isEqualTo(availableBefore);
    }

    @Test
    void shipOfZeroOrNegativeQuantityIsRefusedWithoutChangingAnything() {
        Inventory inventory = inventoryWithOnHand(10);
        inventory.reserve(3);

        assertThatThrownBy(() -> inventory.ship(0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inventory.ship(-1)).isInstanceOf(IllegalStateException.class);

        assertThat(inventory.getQuantityOnHand()).isEqualTo(10);
        assertThat(inventory.getQuantityReserved()).isEqualTo(3);
    }

    @Test
    void shipBeyondTheReservedQuantityIsRefusedWithoutChangingAnything() {
        Inventory inventory = inventoryWithOnHand(10);
        inventory.reserve(3);

        assertThatThrownBy(() -> inventory.ship(4)).isInstanceOf(IllegalStateException.class);

        assertThat(inventory.getQuantityOnHand()).isEqualTo(10);
        assertThat(inventory.getQuantityReserved()).isEqualTo(3);
    }

    @Test
    void shipBeyondThePhysicalQuantityIsRefusedEvenWhenReservedCoversIt() {
        // Livro inconsistente: reservado maior que o físico (só possível por corrupção de dados).
        Inventory inventory = inventoryWithOnHand(10);
        inventory.reserve(8);
        inventory.setOnHand(5);

        assertThatThrownBy(() -> inventory.ship(6)).isInstanceOf(IllegalStateException.class);

        assertThat(inventory.getQuantityOnHand()).isEqualTo(5);
        assertThat(inventory.getQuantityReserved()).isEqualTo(8);
    }

    @Test
    void setOnHandReplacesThePhysicalQuantityAndKeepsTheReserved() {
        Inventory inventory = inventoryWithOnHand(10);
        inventory.reserve(4);

        inventory.setOnHand(6);

        assertThat(inventory.getQuantityOnHand()).isEqualTo(6);
        assertThat(inventory.getQuantityReserved()).isEqualTo(4);
        assertThat(inventory.availableQuantity()).isEqualTo(2);
    }
}
