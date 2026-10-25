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
        while (true) {
            var candidates = afterExpiry == null
                    ? reservations.findExpirationCandidates(InventoryReservationStatus.ACTIVE, cutoff, PageRequest.of(0, batchSize))
                    : reservations.findExpirationCandidatesAfter(InventoryReservationStatus.ACTIVE, cutoff,
                            afterExpiry, afterId, PageRequest.of(0, batchSize));
            for (var candidate : candidates) {
                try {
                    expire.execute(candidate.getOrderId());
                } catch (RuntimeException failure) {
                    log.warn("Inventory reservation expiration failed for order {} ({})",
                            candidate.getOrderId(), failure.getClass().getSimpleName());
                }
            }
            if (candidates.size() < batchSize) return;
            var last = candidates.getLast();
            afterExpiry = last.getExpiresAt();
            afterId = last.getOrderId();
        }
    }
}
