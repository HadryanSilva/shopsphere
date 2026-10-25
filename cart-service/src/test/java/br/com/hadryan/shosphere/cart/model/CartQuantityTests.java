package br.com.hadryan.shosphere.cart.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CartQuantityTests {
    @Test
    void acceptsFirstAdditionAtIntegerLimit() {
        var cart = new Cart();
        cart.addItem(UUID.randomUUID(), Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, cart.getItems().getFirst().getQuantity());
    }

    @Test
    void acceptsIncrementReachingIntegerLimit() {
        var cart = new Cart();
        var productId = UUID.randomUUID();
        cart.addItem(productId, Integer.MAX_VALUE - 1);
        cart.addItem(productId, 1);

        assertEquals(1, cart.getItems().size());
        assertEquals(Integer.MAX_VALUE, cart.getItems().getFirst().getQuantity());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, Integer.MAX_VALUE})
    void rejectsOverflowWithoutChangingExistingItems(int increment) {
        var cart = new Cart();
        var productId = UUID.randomUUID();
        var otherProductId = UUID.randomUUID();
        cart.addItem(productId, Integer.MAX_VALUE);
        cart.addItem(otherProductId, 3);

        assertThrows(ArithmeticException.class, () -> cart.addItem(productId, increment));
        assertEquals(2, cart.getItems().size());
        assertEquals(Integer.MAX_VALUE, cart.getItems().getFirst().getQuantity());
        assertEquals(3, cart.getItems().getLast().getQuantity());
    }
}
