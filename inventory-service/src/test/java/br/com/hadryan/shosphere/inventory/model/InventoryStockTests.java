package br.com.hadryan.shosphere.inventory.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InventoryStockTests {
    @Test
    void consumesAndReleasesOnlyReservedUnitsAndPreservesIntegerBounds() {
        var consumed = new InventoryStock(UUID.randomUUID(), Integer.MAX_VALUE);
        consumed.reserve(Integer.MAX_VALUE);
        consumed.consumeReserved(Integer.MAX_VALUE);
        assertEquals(0, consumed.getOnHand()); assertEquals(0, consumed.getReserved());
        assertThrows(IllegalStateException.class, () -> consumed.consumeReserved(1));
        assertThrows(IllegalStateException.class, () -> consumed.releaseReserved(1));
        assertThrows(IllegalArgumentException.class, () -> consumed.consumeReserved(0));
        assertThrows(IllegalArgumentException.class, () -> consumed.releaseReserved(-1));
        var released = new InventoryStock(UUID.randomUUID(), Integer.MAX_VALUE);
        released.reserve(Integer.MAX_VALUE);
        released.releaseReserved(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, released.getOnHand()); assertEquals(0, released.getReserved());
        assertEquals(Integer.MAX_VALUE, released.getAvailable());
    }
    @Test
    void reservesOnlyAvailableUnitsWithoutOverflowOrChangingOnHand() {
        var stock = new InventoryStock(UUID.randomUUID(), Integer.MAX_VALUE);
        stock.reserve(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, stock.getReserved());
        assertEquals(Integer.MAX_VALUE, stock.getOnHand());
        assertEquals(0, stock.getAvailable());
        assertThrows(br.com.hadryan.shosphere.inventory.exception.StockReservationConflictException.class,
                () -> stock.reserve(1));
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(0));
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(-1));
        assertEquals(Integer.MAX_VALUE, stock.getReserved());
    }
    @Test
    void adjustmentsPreserveReservationsAndRejectInvalidBalancesBeforeMutation() {
        var stock = new InventoryStock(UUID.randomUUID(), 10);
        org.springframework.test.util.ReflectionTestUtils.setField(stock, "reserved", 3);
        stock.adjust(-7);
        assertEquals(3, stock.getOnHand());
        assertEquals(3, stock.getReserved());
        assertEquals(0, stock.getAvailable());
        assertThrows(br.com.hadryan.shosphere.inventory.exception.StockAdjustmentConflictException.class,
                () -> stock.adjust(-1));
        assertEquals(3, stock.getOnHand());
        stock.adjust(Integer.MAX_VALUE - 3);
        assertThrows(br.com.hadryan.shosphere.inventory.exception.StockAdjustmentConflictException.class,
                () -> stock.adjust(1));
        assertThrows(br.com.hadryan.shosphere.inventory.exception.StockAdjustmentConflictException.class,
                () -> stock.adjust(Integer.MIN_VALUE));
        assertEquals(Integer.MAX_VALUE, stock.getOnHand());
        assertThrows(IllegalArgumentException.class, () -> stock.adjust(0));
    }
    @ParameterizedTest
    @ValueSource(ints = {0, Integer.MAX_VALUE})
    void createsStockAtQuantityBoundariesWithoutReservations(int quantity) {
        var productId = UUID.randomUUID();
        var stock = new InventoryStock(productId, quantity);

        assertEquals(productId, stock.getProductId());
        assertEquals(quantity, stock.getOnHand());
        assertEquals(0, stock.getReserved());
        assertEquals(quantity, stock.getAvailable());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void rejectsNegativeInitialQuantity(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> new InventoryStock(UUID.randomUUID(), quantity));
    }

    @Test
    void requiresProductId() {
        assertThrows(NullPointerException.class, () -> new InventoryStock(null, 0));
    }
}
