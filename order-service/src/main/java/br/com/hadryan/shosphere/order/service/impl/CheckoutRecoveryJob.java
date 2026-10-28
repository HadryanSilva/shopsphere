package br.com.hadryan.shosphere.order.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
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
@ConditionalOnProperty(name = {"checkout.snapshot.enabled", "checkout.snapshot.recovery.enabled"}, havingValue = "true")
public class CheckoutRecoveryJob {
    private final CheckoutIntentTransaction transaction;
    private final SnapshotCheckoutUseCase checkout;
    private final int batchSize;
    private final Duration lease;
    private final Duration warnAfter;

    public CheckoutRecoveryJob(CheckoutIntentTransaction transaction, SnapshotCheckoutUseCase checkout,
            @Value("${checkout.snapshot.recovery.batch-size:50}") int batchSize,
            @Value("#{T(java.time.Duration).parse('${checkout.snapshot.recovery.lease:PT60S}')}") Duration lease,
            @Value("#{T(java.time.Duration).parse('${checkout.snapshot.recovery.warn-after:PT15M}')}") Duration warnAfter) {
        Assert.isTrue(batchSize > 0, "Checkout recovery batch size must be positive");
        Assert.isTrue(!lease.isNegative() && !lease.isZero(), "Checkout recovery lease must be positive");
        Assert.isTrue(!warnAfter.isNegative() && !warnAfter.isZero(), "Checkout recovery warning age must be positive");
        this.transaction = transaction;
        this.checkout = checkout;
        this.batchSize = batchSize;
        this.lease = lease;
        this.warnAfter = warnAfter;
    }

    @Scheduled(fixedDelayString = "${checkout.snapshot.recovery.interval:PT60S}",
            initialDelayString = "${checkout.snapshot.recovery.interval:PT60S}")
    public void runOnce() {
        List<UUID> candidates;
        try { candidates = transaction.due(Instant.now(), batchSize); }
        catch (RuntimeException failure) {
            log.warn("Checkout recovery scan unavailable");
            return;
        }
        for (var id : candidates) {
            CheckoutIntentSnapshot claimed;
            try {
                var claim = transaction.claim(id, Instant.now(), lease);
                if (claim.isEmpty()) continue;
                claimed = claim.get();
            } catch (RuntimeException failure) {
                log.warn("Checkout recovery claim unavailable checkoutId={}", id);
                continue;
            }
            // The claim transaction has committed: no database lock/transaction spans HTTP.
            try { checkout.resume(id); }
            catch (RuntimeException failure) { /* Business failures are expected when completing an abort. */ }
            try {
                var result = transaction.recoveryFailed(id, claimed.attemptCount());
                if (result.nextAttemptAt() == null) {
                    log.info("Checkout recovery finished checkoutId={} status={}", id, result.status());
                } else {
                    log.info("Checkout recovery pending checkoutId={} status={} attempts={} category={}",
                            id, result.status(), result.attemptCount(), result.lastFailureCategory());
                    if (!result.createdAt().plus(warnAfter).isAfter(Instant.now()))
                        log.warn("Checkout recovery overdue checkoutId={} status={} attempts={}", id, result.status(), result.attemptCount());
                }
            } catch (RuntimeException failure) {
                // A crashed/unavailable worker leaves a durable claim that becomes eligible again.
                log.warn("Checkout recovery bookkeeping unavailable checkoutId={}", id);
            }
        }
    }
}
