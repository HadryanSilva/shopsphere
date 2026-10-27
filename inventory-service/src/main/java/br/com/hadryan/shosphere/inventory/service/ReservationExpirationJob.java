package br.com.hadryan.shosphere.inventory.service;

import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
@Slf4j
public class ReservationExpirationJob {
    private final InventoryReservationRepository reservations;
    private final ExpireReservationUseCase expire;
    private final int batchSize;

    public ReservationExpirationJob(InventoryReservationRepository reservations, ExpireReservationUseCase expire,
            @Value("${inventory.reservations.expiration.batch-size:50}") int batchSize,
            @Value("${inventory.reservations.expiration.delay:PT60S}") String delay) {
        if (batchSize <= 0 || Duration.parse(delay).compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("Expiration batch size must be positive and delay at least one millisecond");
        }
        this.reservations = reservations;
        this.expire = expire;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${inventory.reservations.expiration.delay:PT60S}")
    public void run() {
        var cutoff = Instant.now();
        Instant afterExpiry = null;
        UUID afterId = null;
        var sweepId = UUID.randomUUID();
        int scanned = 0;
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        boolean completed = false;
        try {
            while (true) {
                var candidates = afterExpiry == null
                        ? reservations.findExpirationCandidates(InventoryReservationStatus.ACTIVE, cutoff, PageRequest.of(0, batchSize))
                        : reservations.findExpirationCandidatesAfter(InventoryReservationStatus.ACTIVE, cutoff,
                                afterExpiry, afterId, PageRequest.of(0, batchSize));
                for (var candidate : candidates) {
                    scanned++;
                    try {
                        if (expire.execute(candidate.getOrderId())) expired++;
                        else skipped++;
                    } catch (RuntimeException failure) {
                        failed++;
                        log.warn("Inventory reservation expiration failed sweepId={} orderId={} failureType={}",
                                sweepId, candidate.getOrderId(), failure.getClass().getSimpleName());
                    }
                }
                if (candidates.size() < batchSize) {
                    completed = true;
                    return;
                }
                var last = candidates.getLast();
                afterExpiry = last.getExpiresAt();
                afterId = last.getOrderId();
            }
        } catch (RuntimeException failure) {
            log.warn("Inventory expiration scan failed sweepId={} failureType={}",
                    sweepId, failure.getClass().getSimpleName());
            throw failure;
        } finally {
            log.info("Inventory expiration sweep sweepId={} candidates={} expired={} skipped={} failed={} completed={}",
                    sweepId, scanned, expired, skipped, failed, completed);
        }
    }
}
