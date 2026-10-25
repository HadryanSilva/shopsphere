package br.com.hadryan.shosphere.inventory.service;

import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationExpirationJobTests {
    @Test void pagesPastFailuresAndRestartsCursorEachSweep() {
        var repository = mock(InventoryReservationRepository.class);
        var expire = mock(ExpireReservationUseCase.class);
        var a = candidate(Instant.EPOCH, new UUID(0, 1));
        var b = candidate(Instant.EPOCH, new UUID(0, 2));
        var c = candidate(Instant.EPOCH.plusSeconds(1), new UUID(0, 3));
        when(repository.findExpirationCandidates(any(), any(), any())).thenReturn(List.of(a, b));
        when(repository.findExpirationCandidatesAfter(any(), any(), any(), any(), any())).thenReturn(List.of(c));
        when(expire.execute(a.getOrderId())).thenThrow(new IllegalStateException("not logged"));
        var job = new ReservationExpirationJob(repository, expire, 2, "PT60S");
        assertDoesNotThrow(job::run);
        verify(expire).execute(a.getOrderId()); verify(expire).execute(b.getOrderId()); verify(expire).execute(c.getOrderId());
        var cutoff = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var page = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        var cursorExpiry = b.getExpiresAt();
        var cursorId = b.getOrderId();
        verify(repository).findExpirationCandidates(eq(InventoryReservationStatus.ACTIVE), cutoff.capture(), page.capture());
        verify(repository).findExpirationCandidatesAfter(eq(InventoryReservationStatus.ACTIVE), eq(cutoff.getValue()),
                eq(cursorExpiry), eq(cursorId), any());
        assertEquals(2, page.getValue().getPageSize());
        assertEquals(0, page.getValue().getPageNumber());
        clearInvocations(repository);
        when(repository.findExpirationCandidates(any(), any(), any())).thenReturn(List.of());
        job.run();
        verify(repository).findExpirationCandidates(any(), any(), any());
    }

    @Test void rejectsInvalidBatchSizeOrInterval() {
        var repository = mock(InventoryReservationRepository.class);
        var expire = mock(ExpireReservationUseCase.class);
        assertThrows(IllegalArgumentException.class, () -> new ReservationExpirationJob(repository, expire, 0, "PT60S"));
        for (var delay : List.of("PT0S", "PT-1S", "PT0.000001S", "invalid")) {
            assertThrows(RuntimeException.class, () -> new ReservationExpirationJob(repository, expire, 50, delay));
        }
    }

    private InventoryReservationRepository.ExpirationCandidate candidate(Instant expiry, UUID order) {
        var candidate = mock(InventoryReservationRepository.ExpirationCandidate.class);
        when(candidate.getOrderId()).thenReturn(order); when(candidate.getExpiresAt()).thenReturn(expiry);
        return candidate;
    }
}
