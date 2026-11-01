package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.model.enums.PaymentWorkPhase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRecoveryJobTests {
    private final PaymentWorkTransaction transaction = mock(PaymentWorkTransaction.class);
    private final PaymentFlow flow = mock(PaymentFlow.class);
    private final Duration minute = Duration.ofSeconds(60), warning = Duration.ofMinutes(15);
    private PaymentRecoveryJob job() { return new PaymentRecoveryJob(transaction, flow, 2, minute, warning, minute); }
    private PaymentWorkClaim claim(UUID id) {
        return new PaymentWorkClaim(UUID.randomUUID(), new PaymentWorkSnapshot(id, PaymentWorkPhase.CAPTURE_PENDING,
                new BigDecimal("25.00"), "EUR", null, false, false, null, 1, null), 0);
    }
    @Test void failuresAreIsolatedAndLogsNeverIncludeExceptionBodyOrClaimToken() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PaymentRecoveryJob.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); events.start(); logger.addAppender(events);
        var first = claim(UUID.randomUUID()); var second = claim(UUID.randomUUID());
        when(transaction.due(any(), eq(2))).thenReturn(List.of(first.work().orderId(), second.work().orderId()));
        when(transaction.claim(eq(first.work().orderId()), any(), eq(minute))).thenReturn(Optional.of(first));
        when(transaction.claim(eq(second.work().orderId()), any(), eq(minute))).thenReturn(Optional.of(second));
        when(flow.resume(first)).thenThrow(new IllegalStateException("sensitive JWT secret address"));
        when(transaction.finish(eq(first), eq(true), any(), eq(warning))).thenThrow(new IllegalStateException("sensitive SQL"));
        when(transaction.finish(eq(second), eq(false), any(), eq(warning))).thenReturn(Optional.of(new PaymentRecoveryResult(second.work(), 1, true)));
        try { assertDoesNotThrow(job()::runOnce); } finally { logger.detachAppender(events); events.stop(); }
        verify(flow).resume(first); verify(flow).resume(second);
        assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains("overdue")));
        assertTrue(events.list.stream().noneMatch(event -> event.getFormattedMessage().contains("sensitive")
                || event.getFormattedMessage().contains(first.token().toString()) || event.getThrowableProxy() != null));
    }
    @Test void unavailableScanAndSkippedOrFailedClaimsDoNotDispatch() {
        when(transaction.due(any(), eq(2))).thenThrow(new IllegalStateException()); assertDoesNotThrow(job()::runOnce);
        var first = UUID.randomUUID(); var second = UUID.randomUUID();
        when(transaction.due(any(), eq(2))).thenReturn(List.of(first, second));
        when(transaction.claim(eq(first), any(), any())).thenThrow(new IllegalStateException());
        when(transaction.claim(eq(second), any(), any())).thenReturn(Optional.empty());
        assertDoesNotThrow(job()::runOnce); verify(transaction).claim(eq(second), any(), any()); verifyNoInteractions(flow);
    }
    @Test void obsoleteFinishDoesNotEmitAnOwnersResult() {
        var claim = claim(UUID.randomUUID()); when(transaction.due(any(), eq(2))).thenReturn(List.of(claim.work().orderId()));
        when(transaction.claim(eq(claim.work().orderId()), any(), any())).thenReturn(Optional.of(claim));
        when(transaction.finish(eq(claim), anyBoolean(), any(), any())).thenReturn(Optional.empty()); assertDoesNotThrow(job()::runOnce);
        verify(flow).resume(claim);
    }
    @Test void limitsAndTransactionScheduleAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentRecoveryJob(transaction, flow, 0, minute, warning, minute));
        assertThrows(IllegalArgumentException.class, () -> new PaymentRecoveryJob(transaction, flow, 1, Duration.ZERO, warning, minute));
        assertThrows(IllegalArgumentException.class, () -> new PaymentRecoveryJob(transaction, flow, 1, minute, Duration.ZERO, minute));
        assertThrows(IllegalArgumentException.class, () -> new PaymentRecoveryJob(transaction, flow, 1, minute, warning, Duration.ZERO));
        var actual = new PaymentWorkTransaction(mock(br.com.hadryan.shosphere.order.repository.StockWorkRepository.class),
                mock(br.com.hadryan.shosphere.order.repository.PaymentWorkRepository.class));
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "initialBackoff", Duration.ZERO);
        assertThrows(IllegalArgumentException.class, actual::validateSchedule);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "initialBackoff", minute);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "maxBackoff", Duration.ofSeconds(30));
        assertThrows(IllegalArgumentException.class, actual::validateSchedule);
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PaymentRecoveryJob.class)
                .withBean(jakarta.persistence.EntityManagerFactory.class, () -> mock(jakarta.persistence.EntityManagerFactory.class))
                .withBean(PaymentWorkTransaction.class, () -> transaction).withBean(PaymentFlow.class, () -> flow);
    }
    @Test void schedulerRequiresSnapshotAndCanBeDisabledWithoutDispatch() {
        runner().withPropertyValues("checkout.snapshot.enabled=false", "payment.recovery.enabled=true")
                .run(context -> assertTrue(context.getBeansOfType(PaymentRecoveryJob.class).isEmpty()));
        runner().withPropertyValues("checkout.snapshot.enabled=true", "payment.recovery.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(PaymentRecoveryJob.class).isEmpty())); verifyNoInteractions(flow);
    }
    @Test void actualSchedulerRunsWhileTwoOtherTasksAreBlocked() {
        var scanned = new CountDownLatch(1); var blocked = new CountDownLatch(2); var release = new CountDownLatch(1);
        when(transaction.due(any(), eq(50))).thenAnswer(call -> { if (blocked.getCount() == 0) scanned.countDown(); return List.of(); });
        runner().withPropertyValues("checkout.snapshot.enabled=true", "payment.recovery.enabled=true", "payment.recovery.interval=PT0.1S")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var scheduler = context.getBean(TaskScheduler.class); for (int n = 0; n < 2; n++) scheduler.schedule(() -> {
                        blocked.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                    }, Instant.now());
                    try { assertTrue(blocked.await(5, TimeUnit.SECONDS)); assertTrue(scanned.await(5, TimeUnit.SECONDS)); }
                    finally { release.countDown(); }
                });
    }
    @Test void stockAndPaymentShareOneSchedulerWithThreeThreads() {
        runner().withUserConfiguration(StockRecoveryJob.class)
                .withBean(StockWorkTransaction.class, () -> mock(StockWorkTransaction.class))
                .withBean(StockReservationFlow.class, () -> mock(StockReservationFlow.class))
                .withPropertyValues("checkout.snapshot.enabled=true", "stock.recovery.enabled=true", "payment.recovery.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure()); assertEquals(1, context.getBeansOfType(TaskScheduler.class).size());
                    assertEquals(3, context.getBean(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
                            .getScheduledThreadPoolExecutor().getCorePoolSize());
                });
    }
}
