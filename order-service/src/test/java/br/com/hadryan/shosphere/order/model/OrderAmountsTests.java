package br.com.hadryan.shosphere.order.model;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrderAmountsTests {
    private Order order() {
        return Order.create(UUID.randomUUID(), UUID.randomUUID(), "EUR",
                new ShippingAddress("Customer", "Street", "City", "1000", "PT"));
    }

    @Test
    void calculatesDecimalSnapshotsAndTotalsWithoutFloatingPointRounding() {
        var order = order();
        order.addItem(UUID.randomUUID(), "First", new BigDecimal("0.10"), 3);
        order.addItem(UUID.randomUUID(), "Second", new BigDecimal("1.200"), 2);
        assertEquals(new BigDecimal("0.30"), order.getItems().getFirst().getLineTotal());
        assertEquals(new BigDecimal("1.20"), order.getItems().getLast().getUnitPrice());
        assertEquals(new BigDecimal("2.70"), order.getSubtotal());
        assertEquals(order.getSubtotal(), order.getTotalAmount());
        assertEquals(new BigDecimal("0.00"), order.getDiscountAmount());
        assertEquals(new BigDecimal("0.00"), order.getShippingAmount());
    }

    @Test
    void subtotalOverflowDoesNotPartiallyMutateAggregate() {
        var order = order();
        order.addItem(UUID.randomUUID(), "First", new BigDecimal("99999999999999999.99"), 1);
        assertThrows(IllegalArgumentException.class, () -> order.addItem(UUID.randomUUID(), "Second", new BigDecimal("0.01"), 1));
        assertEquals(1, order.getItems().size());
        assertEquals(new BigDecimal("99999999999999999.99"), order.getSubtotal());
        assertEquals(order.getSubtotal(), order.getTotalAmount());
    }

    @Test
    void subcentPriceIsRejectedWithoutAddingItem() {
        var order = order();
        assertThrows(IllegalArgumentException.class, () -> order.addItem(UUID.randomUUID(), "Product", new BigDecimal("0.001"), 1));
        assertTrue(order.getItems().isEmpty());
        assertEquals(new BigDecimal("0.00"), order.getTotalAmount());
    }
}
