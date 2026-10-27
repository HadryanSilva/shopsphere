package br.com.hadryan.shosphere.order.model;

import br.com.hadryan.shosphere.order.model.enums.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutIntentTests {
    private ShippingAddress address(String line2) {
        return new ShippingAddress("Customer", "Street", line2, "Lisbon", "1000-001", "PT");
    }

    private CheckoutIntent intent() { return CheckoutIntent.start(UUID.randomUUID(), UUID.randomUUID(), address(null)); }

    @Test
    void addressComparisonPreservesAllSixFieldsAndNullVersusEmpty() {
        var intent = intent();
        assertTrue(intent.sameAddress(address(null)));
        assertFalse(intent.sameAddress(address("")));
        var changes = List.of(new ShippingAddress("Other", "Street", null, "Lisbon", "1000-001", "PT"),
                new ShippingAddress("Customer", "Other", null, "Lisbon", "1000-001", "PT"),
                new ShippingAddress("Customer", "Street", null, "Other", "1000-001", "PT"),
                new ShippingAddress("Customer", "Street", null, "Lisbon", "Other", "PT"),
                new ShippingAddress("Customer", "Street", null, "Lisbon", "1000-001", "ES"));
        for (var change : changes) assertFalse(intent.sameAddress(change));
    }

    @Test
    void snapshotIsCopiedAndCannotBeOverwrittenAfterPreparation() {
        var intent = intent();
        var productId = UUID.randomUUID();
        var cartId = UUID.randomUUID();
        var original = new ArrayList<>(List.of(new CheckoutIntentItem(productId, Integer.MAX_VALUE)));
        intent.prepare(cartId, original);
        original.clear();
        assertEquals(1, intent.getItems().size());
        assertTrue(intent.sameSnapshot(cartId, List.of(new CheckoutIntentItem(productId, Integer.MAX_VALUE))));
        assertThrows(UnsupportedOperationException.class, () -> intent.getItems().clear());
        assertThrows(IllegalStateException.class, () -> intent.prepare(cartId, List.of()));
    }

    @Test
    void abortDecisionPreventsAnyLaterOrderAndKeepsSafeFailureReason() {
        var intent = intent();
        intent.requestAbort(CheckoutFailureReason.CART_NOT_FOUND);
        assertEquals(404, intent.getFailureReason().httpStatus());
        assertThrows(IllegalStateException.class, () -> intent.orderPersisted(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> intent.prepare(UUID.randomUUID(), List.of()));
        intent.aborted();
        assertNull(intent.getNextAttemptAt());
        assertThrows(IllegalStateException.class, () -> intent.recordFailure(CheckoutRetryCategory.UPSTREAM_UNAVAILABLE, Instant.now()));
    }

    @Test
    void persistedOrderCannotBeAbortedAndCompletionStopsScheduling() {
        var intent = intent();
        intent.prepare(UUID.randomUUID(), List.of(new CheckoutIntentItem(UUID.randomUUID(), 2)));
        intent.orderPersisted(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> intent.requestAbort(CheckoutFailureReason.PRODUCT_CONFLICT));
        intent.completed();
        assertNull(intent.getNextAttemptAt());
        assertEquals(CheckoutIntentStatus.COMPLETED, intent.getStatus());
    }

    @Test
    void retryMetadataUsesSafeEnumAndDatabaseTimestampPrecision() {
        var intent = intent();
        var next = Instant.parse("2026-10-27T12:00:00.123456789Z");
        intent.recordFailure(CheckoutRetryCategory.UPSTREAM_UNAVAILABLE, next);
        assertEquals(1, intent.getAttemptCount());
        assertEquals(Instant.parse("2026-10-27T12:00:00.123456Z"), intent.getNextAttemptAt());
        assertEquals(CheckoutRetryCategory.UPSTREAM_UNAVAILABLE, intent.getLastFailureCategory());
    }

    @Test
    void invalidSnapshotsDoNotPartiallyPrepareIntent() {
        var intent = intent();
        var productId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> intent.prepare(UUID.randomUUID(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> intent.prepare(UUID.randomUUID(),
                List.of(new CheckoutIntentItem(productId, 1), new CheckoutIntentItem(productId, 2))));
        assertEquals(CheckoutIntentStatus.PREPARING, intent.getStatus());
        assertNull(intent.getCartId());
        assertTrue(intent.getItems().isEmpty());
    }
}
