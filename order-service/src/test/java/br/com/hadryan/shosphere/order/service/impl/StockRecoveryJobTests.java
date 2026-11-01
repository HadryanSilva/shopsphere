package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.model.enums.StockWorkPhase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockRecoveryJobTests {
    private final StockWorkTransaction transaction = mock(StockWorkTransaction.class);
    private final StockReservationFlow flow = mock(StockReservationFlow.class);
    private final Duration minute = Duration.ofSeconds(60), warning = Duration.ofMinutes(15);
    private StockRecoveryJob job() { return new StockRecoveryJob(transaction, flow, 2, minute, warning, minute, minute); }
    private StockWorkClaim claim(UUID id) {
        var work = new StockWorkSnapshot(id, UUID.randomUUID(), StockWorkPhase.RESERVE_PENDING, false, null, null,
                null, null, null, Instant.now(), 0, null, Instant.now().minusSeconds(1000), Instant.now(), 1, List.of());
        return new StockWorkClaim(UUID.randomUUID(), work);
    }

    @Test void failuresAreIsolatedAndLogsContainOnlySafeMetadata() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(StockRecoveryJob.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start(); logger.addAppender(events);
        var first = UUID.randomUUID(); var second = UUID.randomUUID();
        var a = claim(first); var b = claim(second);
        when(transaction.due(any(), eq(2))).thenReturn(List.of(first, second));
        when(transaction.claim(eq(first), any(), eq(minute))).thenReturn(Optional.of(a));
        when(transaction.claim(eq(second), any(), eq(minute))).thenReturn(Optional.of(b));
        when(flow.resume(first)).thenThrow(new IllegalStateException("sensitive JWT address downstream"));
        when(transaction.finish(eq(a), eq(true), any(), eq(warning))).thenThrow(new IllegalStateException("sensitive SQL"));
        when(transaction.finish(eq(b), eq(false), any(), eq(warning))).thenReturn(Optional.of(new StockRecoveryResult(b.work(), true)));
        try { assertDoesNotThrow(job()::runOnce); }
        finally { logger.detachAppender(events); events.stop(); }
        verify(flow).resume(first); verify(flow).resume(second);
        verify(transaction).finish(eq(a), eq(true), any(), eq(warning));
        assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains("overdue")));
        assertTrue(events.list.stream().noneMatch(event -> event.getFormattedMessage().contains("sensitive") || event.getThrowableProxy() != null));
    }

    @Test void unavailableScanAndSkippedOrFailedClaimsNeverDispatchHttp() {
        when(transaction.due(any(), eq(2))).thenThrow(new IllegalStateException());
        assertDoesNotThrow(job()::runOnce);
        var a = UUID.randomUUID(); var b = UUID.randomUUID();
        when(transaction.due(any(), eq(2))).thenReturn(List.of(a, b));
        when(transaction.claim(eq(a), any(), any())).thenThrow(new IllegalStateException());
        when(transaction.claim(eq(b), any(), any())).thenReturn(Optional.empty());
        assertDoesNotThrow(job()::runOnce);
        verify(transaction).claim(eq(b), any(), any());
        verifyNoInteractions(flow);
    }

    @Test void obsoleteClaimDoesNotLogOrFinishNewOwnersWork() {
        var id = UUID.randomUUID(); var claim = claim(id);
        when(transaction.due(any(), eq(2))).thenReturn(List.of(id));
        when(transaction.claim(eq(id), any(), any())).thenReturn(Optional.of(claim));
        when(transaction.finish(eq(claim), eq(false), any(), any())).thenReturn(Optional.empty());
        assertDoesNotThrow(job()::runOnce);
        verify(flow).resume(id);
    }

    @Test void operationalLimitsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new StockRecoveryJob(transaction, flow, 0, minute, warning, minute, minute));
        assertThrows(IllegalArgumentException.class, () -> new StockRecoveryJob(transaction, flow, 1, Duration.ZERO, warning, minute, minute));
        assertThrows(IllegalArgumentException.class, () -> new StockRecoveryJob(transaction, flow, 1, minute, Duration.ZERO, minute, minute));
        assertThrows(IllegalArgumentException.class, () -> new StockRecoveryJob(transaction, flow, 1, minute, warning, Duration.ZERO, minute));
        assertThrows(IllegalArgumentException.class, () -> new StockRecoveryJob(transaction, flow, 1, minute, warning, minute, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new StockRecoveryJob(transaction, flow, 1, minute, warning, minute, Duration.ofSeconds(61)));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(StockRecoveryJob.class)
                .withBean(jakarta.persistence.EntityManagerFactory.class, () -> mock(jakarta.persistence.EntityManagerFactory.class))
                .withBean(StockWorkTransaction.class, () -> transaction).withBean(StockReservationFlow.class, () -> flow);
    }

    @Test void schedulerIsInactiveWithoutSnapshotOrWhenExplicitlyDisabled() {
        runner().withPropertyValues("checkout.snapshot.enabled=false", "stock.recovery.enabled=true")
                .run(context -> assertTrue(context.getBeansOfType(StockRecoveryJob.class).isEmpty()));
        runner().withPropertyValues("checkout.snapshot.enabled=true", "stock.recovery.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(StockRecoveryJob.class).isEmpty()));
        verify(transaction, never()).due(any(), anyInt());
        verify(transaction, never()).claim(any(), any(), any());
        verify(flow, never()).resume(any());
    }

    @Test void enabledSchedulerActuallyRunsWithoutPublicReplay() {
        var scanned = new CountDownLatch(1);
        var blocked = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(transaction.due(any(), eq(50))).thenAnswer(call -> {
            if (blocked.getCount() == 0 && release.getCount() > 0) scanned.countDown();
            return List.of();
        });
        runner().withPropertyValues("checkout.snapshot.enabled=true", "stock.recovery.enabled=true",
                        "stock.recovery.interval=PT0.1S", "stock.recovery.monitor-interval=PT1S")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var scheduler = context.getBean(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
                    assertEquals(3, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
                    scheduler.schedule(() -> {
                        blocked.countDown();
                        try { release.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }, Instant.now());
                    try {
                        assertTrue(blocked.await(5, TimeUnit.SECONDS));
                        assertTrue(scanned.await(5, TimeUnit.SECONDS));
                    } finally { release.countDown(); }
                });
    }
}
