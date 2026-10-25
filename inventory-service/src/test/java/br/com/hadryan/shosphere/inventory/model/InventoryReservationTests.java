package br.com.hadryan.shosphere.inventory.model;

import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class InventoryReservationTests {
    @ParameterizedTest
    @CsvSource({"ACTIVE,-1,false", "ACTIVE,0,true", "ACTIVE,1,true", "CONFIRMED,1,false",
            "RELEASED,1,false", "EXPIRED,1,false"})
    void expiresOnlyActiveReservationsAtOrAfterDeadline(InventoryReservationStatus initial, int offset, boolean changed) {
        var reservation = new InventoryReservation(UUID.randomUUID(), "service", Instant.now().plusSeconds(900),
                List.of(new InventoryReservationItem(UUID.randomUUID(), 1)));
        org.springframework.test.util.ReflectionTestUtils.setField(reservation, "status", initial);
        assertEquals(changed, reservation.expire(reservation.getExpiresAt().plusSeconds(offset)));
        assertEquals(changed ? InventoryReservationStatus.EXPIRED : initial, reservation.getStatus());
    }
    @ParameterizedTest
    @CsvSource({"ACTIVE,-1,true,CONFIRMED", "ACTIVE,0,false,ACTIVE", "ACTIVE,1,false,ACTIVE",
            "CONFIRMED,-1,true,CONFIRMED", "CONFIRMED,1,true,CONFIRMED",
            "RELEASED,-1,false,RELEASED", "EXPIRED,1,false,EXPIRED"})
    void confirmationFollowsStateAndDeadlineMatrix(InventoryReservationStatus initial, int secondsAfterExpiry,
                                                  boolean allowed, InventoryReservationStatus expected) {
        var reservation = new InventoryReservation(UUID.randomUUID(), "service", Instant.now().plusSeconds(900),
                List.of(new InventoryReservationItem(UUID.randomUUID(), 1)));
        org.springframework.test.util.ReflectionTestUtils.setField(reservation, "status", initial);
        var now = reservation.getExpiresAt().plusSeconds(secondsAfterExpiry);
        if (allowed) assertEquals(initial == InventoryReservationStatus.ACTIVE, reservation.confirm(now));
        else assertThrows(br.com.hadryan.shosphere.inventory.exception.ReservationStateConflictException.class,
                () -> reservation.confirm(now));
        assertEquals(expected, reservation.getStatus());
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,-1,true,RELEASED", "ACTIVE,0,true,EXPIRED", "ACTIVE,1,true,EXPIRED",
            "CONFIRMED,-1,false,CONFIRMED", "CONFIRMED,1,false,CONFIRMED",
            "RELEASED,-1,true,RELEASED", "RELEASED,1,true,RELEASED", "EXPIRED,1,true,EXPIRED"})
    void releaseFollowsStateAndDeadlineMatrix(InventoryReservationStatus initial, int secondsAfterExpiry,
                                             boolean allowed, InventoryReservationStatus expected) {
        var reservation = new InventoryReservation(UUID.randomUUID(), "service", Instant.now().plusSeconds(900),
                List.of(new InventoryReservationItem(UUID.randomUUID(), 1)));
        org.springframework.test.util.ReflectionTestUtils.setField(reservation, "status", initial);
        var now = reservation.getExpiresAt().plusSeconds(secondsAfterExpiry);
        if (allowed) assertEquals(initial == InventoryReservationStatus.ACTIVE, reservation.release(now));
        else assertThrows(br.com.hadryan.shosphere.inventory.exception.ReservationStateConflictException.class,
                () -> reservation.release(now));
        assertEquals(expected, reservation.getStatus());
    }

    private final Instant expiry = Instant.now().plusSeconds(900);

    @Test void createsActiveReservationWithImmutableIntentAndNoAssignedVersion() {
        var item = new InventoryReservationItem(UUID.randomUUID(), Integer.MAX_VALUE);
        var input = new ArrayList<>(List.of(item));
        var order = UUID.randomUUID();
        var reservation = new InventoryReservation(order, "shopsphere-order", expiry, input);
        input.clear();
        assertEquals(order, reservation.getOrderId());
        assertEquals("shopsphere-order", reservation.getServiceClientId());
        assertEquals(InventoryReservationStatus.ACTIVE, reservation.getStatus());
        assertNull(reservation.getVersion());
        assertEquals(Integer.MAX_VALUE, reservation.getItems().getFirst().getQuantity());
        assertThrows(UnsupportedOperationException.class, () -> reservation.getItems().clear());
        assertThrows(IllegalStateException.class, () -> reservation.initializeItems(List.of(item)));
        reservation.onCreate();
        assertEquals(reservation.getCreatedAt(), reservation.getUpdatedAt());
        assertTrue(reservation.getExpiresAt().isAfter(reservation.getCreatedAt()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveQuantity(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationItem(UUID.randomUUID(), quantity));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingOrBlankServiceClient(String client) {
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservation(UUID.randomUUID(), client,
                expiry, List.of(new InventoryReservationItem(UUID.randomUUID(), 1))));
    }

    @Test void validatesClientLengthAndRequiredIdentifiers() {
        var items = List.of(new InventoryReservationItem(UUID.randomUUID(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryReservation(UUID.randomUUID(), "x".repeat(256), expiry, items));
        assertDoesNotThrow(() -> new InventoryReservation(UUID.randomUUID(), "x".repeat(255), expiry, items));
        assertThrows(NullPointerException.class, () -> new InventoryReservationItem(null, 1));
        assertThrows(NullPointerException.class, () -> new InventoryReservation(null, "service", expiry, items));
        assertThrows(NullPointerException.class, () -> new InventoryReservation(UUID.randomUUID(), "service", null, items));
    }

    @Test void rejectsEmptyOversizeNullAndDuplicateItemsWithoutSumming() {
        var id = UUID.randomUUID();
        var item = new InventoryReservationItem(id, 1);
        for (var items : Arrays.asList(null, List.<InventoryReservationItem>of(),
                Arrays.asList(item, null), List.of(item, new InventoryReservationItem(id, 2)),
                IntStream.range(0, 101).mapToObj(i -> new InventoryReservationItem(UUID.randomUUID(), 1)).toList())) {
            assertThrows(IllegalArgumentException.class,
                    () -> new InventoryReservation(UUID.randomUUID(), "service", expiry, items));
        }
        assertDoesNotThrow(() -> new InventoryReservation(UUID.randomUUID(), "service", expiry,
                IntStream.range(0, 100).mapToObj(i -> new InventoryReservationItem(UUID.randomUUID(), 1)).toList()));
    }

    @Test void cannotPersistAnAlreadyExpiredReservation() {
        var reservation = new InventoryReservation(UUID.randomUUID(), "service", Instant.EPOCH,
                List.of(new InventoryReservationItem(UUID.randomUUID(), 1)));
        assertThrows(IllegalArgumentException.class, reservation::onCreate);
    }
}
