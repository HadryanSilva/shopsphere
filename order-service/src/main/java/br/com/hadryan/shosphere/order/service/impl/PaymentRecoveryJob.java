package br.com.hadryan.shosphere.order.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.Assert;
import java.time.*;
import java.util.*;

@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = {"checkout.snapshot.enabled", "payment.recovery.enabled"}, havingValue = "true")
public class PaymentRecoveryJob {
    private final PaymentWorkTransaction transaction;
    private final PaymentFlow flow;
    private final int batchSize;
    private final Duration lease, warnAfter;

    public PaymentRecoveryJob(PaymentWorkTransaction transaction, PaymentFlow flow,
            @Value("${payment.recovery.batch-size:50}") int batchSize,
            @Value("#{T(java.time.Duration).parse('${payment.recovery.lease:PT60S}')}") Duration lease,
            @Value("#{T(java.time.Duration).parse('${payment.recovery.warn-after:PT15M}')}") Duration warnAfter,
            @Value("#{T(java.time.Duration).parse('${payment.recovery.interval:PT60S}')}") Duration interval) {
        Assert.isTrue(batchSize > 0 && lease.isPositive() && warnAfter.isPositive() && interval.isPositive(), "Invalid payment recovery limits");
        this.transaction = transaction; this.flow = flow; this.batchSize = batchSize; this.lease = lease; this.warnAfter = warnAfter;
    }
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(TaskScheduler.class)
    TaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("checkout-recovery-"); return scheduler;
    }
    @Scheduled(fixedDelayString = "${payment.recovery.interval:PT60S}", initialDelayString = "${payment.recovery.interval:PT60S}")
    public void runOnce() {
        List<UUID> ids;
        try { ids = transaction.due(Instant.now(), batchSize); }
        catch (RuntimeException failure) { log.warn("Payment recovery scan unavailable"); return; }
        for (var id : ids) {
            PaymentWorkClaim claim;
            try {
                var candidate = transaction.claim(id, Instant.now(), lease);
                if (candidate.isEmpty()) continue;
                claim = candidate.get();
            } catch (RuntimeException failure) { log.warn("Payment recovery claim unavailable orderId={}", id); continue; }
            boolean failed = false;
            try { flow.resume(claim); } catch (RuntimeException failure) { failed = true; }
            try {
                transaction.finish(claim, failed, Instant.now(), warnAfter).ifPresent(result -> {
                    log.info("Payment recovery result orderId={} phase={} attempts={} category={}", id, result.work().phase(), result.attempts(), result.work().lastFailureCategory());
                    if (result.warnOverdue()) log.warn("Payment recovery overdue orderId={} phase={} attempts={} category={}", id, result.work().phase(), result.attempts(), result.work().lastFailureCategory());
                });
            } catch (RuntimeException failure) { log.warn("Payment recovery bookkeeping unavailable orderId={}", id); }
        }
    }
}
