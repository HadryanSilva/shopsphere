package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.model.enums.CheckoutIntentStatus;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckoutRecoveryJobTests {
    private final CheckoutIntentTransaction transaction = mock(CheckoutIntentTransaction.class);
    private final SnapshotCheckoutUseCase checkout = mock(SnapshotCheckoutUseCase.class);
    private final CheckoutRecoveryJob job = new CheckoutRecoveryJob(transaction, checkout, 2,
            Duration.ofSeconds(60), Duration.ofMinutes(15));

    private CheckoutIntentSnapshot claim(UUID id) {
        return new CheckoutIntentSnapshot(id, UUID.randomUUID(), UUID.randomUUID().toString(), null,
                CheckoutIntentStatus.PREPARING, null, List.of(), null, null, Instant.now(), 0, null,
                Instant.now().minusSeconds(1000), Instant.now());
    }

    @Test
    void failedIntentDoesNotPreventRemainingBatchAndLogsNeverUseExceptionText() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CheckoutRecoveryJob.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(transaction.due(any(), eq(2))).thenReturn(List.of(first, second));
        when(transaction.claim(eq(first), any(), any())).thenReturn(Optional.of(claim(first)));
        when(transaction.claim(eq(second), any(), any())).thenReturn(Optional.of(claim(second)));
        doThrow(new IllegalStateException("sensitive downstream body")).when(checkout).resume(first);
        when(transaction.recoveryFailed(first, 0)).thenThrow(new IllegalStateException("sensitive SQL"));
        when(transaction.recoveryFailed(second, 0)).thenReturn(claim(second));
        try { assertDoesNotThrow(job::runOnce); }
        finally { logger.detachAppender(events); events.stop(); }
        assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains("overdue")));
        assertTrue(events.list.stream().noneMatch(event -> event.getFormattedMessage().contains("sensitive") || event.getThrowableProxy() != null));
        verify(checkout).resume(first);
        verify(checkout).resume(second);
        verify(transaction).recoveryFailed(first, 0);
        verify(transaction).recoveryFailed(second, 0);
    }

    @Test
    void skippedAndFailedClaimsDoNotResumeOrStopOtherClaims() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(transaction.due(any(), eq(2))).thenReturn(List.of(first, second));
        when(transaction.claim(eq(first), any(), any())).thenThrow(new IllegalStateException());
        when(transaction.claim(eq(second), any(), any())).thenReturn(Optional.empty());
        assertDoesNotThrow(job::runOnce);
        verify(transaction).claim(eq(second), any(), any());
        verifyNoInteractions(checkout);
    }

    @Test
    void unavailableScanIsContained() {
        when(transaction.due(any(), eq(2))).thenThrow(new IllegalStateException());
        assertDoesNotThrow(job::runOnce);
        verifyNoInteractions(checkout);
    }

    @Test
    void invalidOperationalLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutRecoveryJob(transaction, checkout,
                0, Duration.ofSeconds(60), Duration.ofMinutes(15)));
        assertThrows(IllegalArgumentException.class, () -> new CheckoutRecoveryJob(transaction, checkout,
                1, Duration.ZERO, Duration.ofMinutes(15)));
        assertThrows(IllegalArgumentException.class, () -> new CheckoutRecoveryJob(transaction, checkout,
                1, Duration.ofSeconds(60), Duration.ZERO));
    }
}
