package br.com.hadryan.shosphere.inventory.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InventoryReservationCoordinationTests {
    @Test void snapshotIsImmutableAndComparisonIgnoresOrderButPreservesQuantities() {
        var a = new InventoryReservationItem(UUID.randomUUID(), 1);
        var b = new InventoryReservationItem(UUID.randomUUID(), Integer.MAX_VALUE);
        var supplied = new ArrayList<>(List.of(a, b));
        var root = new InventoryReservationCoordination(UUID.randomUUID(), "shopsphere-order", supplied);
        supplied.clear();
        assertTrue(root.sameItems(List.of(b, a)));
        assertFalse(root.sameItems(List.of(new InventoryReservationItem(a.getProductId(), 2), b)));
        assertThrows(UnsupportedOperationException.class, () -> root.getItems().clear());
        assertThrows(IllegalStateException.class, () -> root.initializeItems(List.of(a)));
    }

    @Test void abortIsPermanentAndReplayDoesNotChangeTimestamp() {
        var root = new InventoryReservationCoordination(UUID.randomUUID(), "shopsphere-order",
                List.of(new InventoryReservationItem(UUID.randomUUID(), 1)));
        assertNull(root.getAbortedAt());
        assertTrue(root.abort(Instant.now()));
        var original = root.getAbortedAt();
        assertFalse(root.abort(Instant.now().plusSeconds(600)));
        assertEquals(original, root.getAbortedAt());
        assertThrows(IllegalStateException.class, () -> root.initializeItems(root.getItems()));
    }

    @Test void invalidIdentitiesSnapshotsAndAbortTimeAreRejected() {
        var item = new InventoryReservationItem(UUID.randomUUID(), 1);
        for (var client : new String[]{null, "", " ", "x".repeat(256)})
            assertThrows(IllegalArgumentException.class, () -> new InventoryReservationCoordination(UUID.randomUUID(), client, List.of(item)));
        assertThrows(NullPointerException.class, () -> new InventoryReservationCoordination(null, "shopsphere-order", List.of(item)));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationCoordination(UUID.randomUUID(), "shopsphere-order", null));
        for (var items : List.of(List.<InventoryReservationItem>of(), List.of(item, item), java.util.Collections.singletonList((InventoryReservationItem) null),
                java.util.stream.IntStream.range(0, 101).mapToObj(i -> new InventoryReservationItem(UUID.randomUUID(), 1)).toList()))
            assertThrows(IllegalArgumentException.class, () -> new InventoryReservationCoordination(UUID.randomUUID(), "shopsphere-order", items));
        var root = new InventoryReservationCoordination(UUID.randomUUID(), "shopsphere-order", List.of(item));
        assertThrows(IllegalArgumentException.class, () -> root.abort(root.getCreatedAt().minusSeconds(1)));
        assertNull(root.getAbortedAt());
    }

    @Test void acceptsOneHundredDistinctProductsAtQuantityLimit() {
        var items = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> new InventoryReservationItem(UUID.randomUUID(), Integer.MAX_VALUE)).toList();
        assertEquals(100, new InventoryReservationCoordination(UUID.randomUUID(), "shopsphere-order", items).getItems().size());
    }
}
