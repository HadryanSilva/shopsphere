package br.com.hadryan.shosphere.order.model;

import br.com.hadryan.shosphere.order.model.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderLegacyStatusTests {
    @Test
    void completedLegacyOrderCannotBeCancelled() {
        var order = new Order();
        order.setStatus(OrderStatus.COMPLETED);

        assertThrows(IllegalStateException.class, order::cancel);
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
    }

    @Test
    void newOrderUsesPendingStockStatus() {
        assertEquals(OrderStatus.PENDING_STOCK, new Order().getStatus());
    }
}
