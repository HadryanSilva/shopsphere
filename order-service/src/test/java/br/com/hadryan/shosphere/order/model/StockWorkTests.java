package br.com.hadryan.shosphere.order.model;

import br.com.hadryan.shosphere.order.model.enums.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StockWorkTests {
    private final Instant created = Instant.parse("2026-10-31T12:00:00Z");
    private final Instant expires = created.plusSeconds(900);
    private StockWork ready() {
        var work = StockWork.waiting(UUID.randomUUID(), UUID.randomUUID());
        work.cartCompleted();
        return work;
    }

    @Test void waitingRequiresCartAndRetainsStableIdentity() {
        var work = StockWork.waiting(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(StockWorkPhase.WAITING_CART, work.getPhase());
        assertNotNull(work.getNextAttemptAt());
        assertThrows(IllegalStateException.class, () -> work.observe(StockReservationStatus.ACTIVE, created, expires, created, expires));
        work.cartCompleted();
        var updated = work.getUpdatedAt();
        work.cartCompleted();
        assertEquals(updated, work.getUpdatedAt());
        assertEquals(StockWorkPhase.RESERVE_PENDING, work.getPhase());
    }

    @Test void abortDecisionCannotBeUndoneByLateActiveOrDifferentReason() {
        var work = ready();
        work.requestAbort(StockWorkReason.RESERVATION_REJECTED);
        var updated = work.getUpdatedAt();
        work.requestAbort(StockWorkReason.RESERVATION_EXPIRED);
        work.observe(StockReservationStatus.ACTIVE, created, expires, created, expires);
        assertTrue(work.isAbortRequested());
        assertEquals(StockWorkReason.RESERVATION_REJECTED, work.getReason());
        assertEquals(StockWorkPhase.ABORT_PENDING, work.getPhase());
        assertEquals(updated, work.getUpdatedAt());
        assertNull(work.getReservationStatus());
        work.abortedWithoutReservation();
        assertEquals(StockWorkPhase.CLOSED, work.getPhase());
        assertNull(work.getNextAttemptAt());
        work.observe(StockReservationStatus.ACTIVE, created, expires, created, expires);
        assertEquals(StockWorkPhase.CLOSED, work.getPhase());
    }

    @Test void knownReservationCannotBeClosedWithNullProof() {
        var work = ready();
        work.observe(StockReservationStatus.ACTIVE, created, expires, created, expires);
        work.requestAbort(StockWorkReason.RESERVATION_EXPIRED);
        assertThrows(IllegalStateException.class, work::abortedWithoutReservation);
        work.observe(StockReservationStatus.EXPIRED, created, expires, expires, expires);
        assertEquals(StockWorkPhase.CLOSED, work.getPhase());
        assertEquals(StockReservationStatus.EXPIRED, work.getReservationStatus());
    }

    @Test void replayCannotRenewReservationOrReverseReview() {
        var work = ready();
        work.observe(StockReservationStatus.ACTIVE, created, expires, created, expires);
        work.requestAbort(StockWorkReason.RESERVATION_REJECTED);
        work.observe(StockReservationStatus.ACTIVE, created, expires.plusSeconds(1), created, expires);
        assertEquals(StockWorkPhase.REVIEW_REQUIRED, work.getPhase());
        assertTrue(work.isAbortRequested());
        assertEquals(expires, work.getReservationExpiresAt());
        work.cartCompleted();
        work.observe(StockReservationStatus.ACTIVE, created, expires, created, expires);
        assertEquals(StockWorkPhase.REVIEW_REQUIRED, work.getPhase());
    }

    @Test void unexpectedConfirmedRequiresReviewWithoutRecordingSuccess() {
        var work = ready();
        work.observe(StockReservationStatus.CONFIRMED, created, expires, created, expires);
        assertEquals(StockWorkPhase.REVIEW_REQUIRED, work.getPhase());
        assertEquals(StockFailureCategory.UNEXPECTED_CONFIRMED, work.getLastFailureCategory());
        assertNull(work.getReservationStatus());
    }

    @Test void retriesKeepPhaseAndAbortDecisionAndIgnoreObsoletePhase() {
        var work = ready();
        work.recordFailure(StockWorkPhase.RESERVE_PENDING, StockFailureCategory.AUTHENTICATION_CONFIGURATION, expires);
        assertEquals(1, work.getAttemptCount());
        assertEquals(StockWorkPhase.RESERVE_PENDING, work.getPhase());
        work.requestAbort(StockWorkReason.RESERVATION_REJECTED);
        work.recordFailure(StockWorkPhase.RESERVE_PENDING, StockFailureCategory.UPSTREAM_UNAVAILABLE, expires);
        assertEquals(1, work.getAttemptCount());
        work.recordFailure(StockWorkPhase.ABORT_PENDING, StockFailureCategory.UPSTREAM_UNAVAILABLE, expires);
        assertEquals(2, work.getAttemptCount());
        assertTrue(work.isAbortRequested());
        assertEquals(expires, work.getNextAttemptAt());
    }
}
