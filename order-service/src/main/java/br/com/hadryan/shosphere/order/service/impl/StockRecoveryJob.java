package br.com.hadryan.shosphere.order.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.Assert;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = {"checkout.snapshot.enabled", "stock.recovery.enabled"}, havingValue = "true")
public class StockRecoveryJob {
    private final StockWorkTransaction transaction;
    private final StockReservationFlow flow;
    private final int batchSize;
    private final Duration lease;
    private final Duration warnAfter;

    public StockRecoveryJob(StockWorkTransaction transaction, StockReservationFlow flow,
            @Value("${stock.recovery.batch-size:50}") int batchSize,
            @Value("#{T(java.time.Duration).parse('${stock.recovery.lease:PT60S}')}") Duration lease,
            @Value("#{T(java.time.Duration).parse('${stock.recovery.warn-after:PT15M}')}") Duration warnAfter,
            @Value("#{T(java.time.Duration).parse('${stock.recovery.interval:PT60S}')}") Duration interval,
            @Value("#{T(java.time.Duration).parse('${stock.recovery.monitor-interval:PT60S}')}") Duration monitorInterval) {
        Assert.isTrue(batchSize > 0, "Stock recovery batch size must be positive");
        Assert.isTrue(lease.isPositive(), "Stock recovery lease must be positive");
        Assert.isTrue(warnAfter.isPositive(), "Stock recovery warning age must be positive");
        Assert.isTrue(monitorInterval.isPositive() && monitorInterval.compareTo(Duration.ofSeconds(60)) <= 0
                && interval.isPositive() && interval.compareTo(monitorInterval) <= 0,
                "Stock scan interval must be positive and no greater than monitoring interval (at most PT60S)");
        this.transaction = transaction;
        this.flow = flow;
        this.batchSize = batchSize;
        this.lease = lease;
        this.warnAfter = warnAfter;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(TaskScheduler.class)
    TaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        // Cart completion and stock recovery must not share a single blocked HTTP execution thread.
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("checkout-recovery-");
        return scheduler;
    }

    @Scheduled(fixedDelayString = "${stock.recovery.interval:PT60S}", initialDelayString = "${stock.recovery.interval:PT60S}")
    public void runOnce() {
        List<UUID> candidates;
        try { candidates = transaction.due(Instant.now(), batchSize); }
        catch (RuntimeException failure) {
            log.warn("Stock recovery scan unavailable");
            return;
        }
        for (var id : candidates) {
            StockWorkClaim claimed;
            try {
                var candidate = transaction.claim(id, Instant.now(), lease);
                if (candidate.isEmpty()) continue;
                claimed = candidate.get();
            } catch (RuntimeException failure) {
                log.warn("Stock recovery claim unavailable orderId={}", id);
                continue;
            }
            // The claim has committed before HTTP. A lease cannot cancel an already dispatched request.
            boolean failed = false;
            try { flow.resume(id); }
            catch (RuntimeException failure) { failed = true; }
            try {
                transaction.finish(claimed, failed, Instant.now(), warnAfter).ifPresent(result -> {
                    var work = result.work();
                    log.info("Stock recovery result orderId={} checkoutId={} phase={} attempts={} category={}",
                            id, work.checkoutId(), work.phase(), work.attemptCount(), work.lastFailureCategory());
                    if (result.warnOverdue())
                        log.warn("Stock recovery overdue orderId={} checkoutId={} phase={} attempts={} category={}",
                                id, work.checkoutId(), work.phase(), work.attemptCount(), work.lastFailureCategory());
                });
            } catch (RuntimeException failure) {
                // A crash or unavailable database leaves the claim eligible again after its durable lease.
                log.warn("Stock recovery bookkeeping unavailable orderId={}", id);
            }
        }
    }
}
