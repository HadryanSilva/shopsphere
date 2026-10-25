package br.com.hadryan.shosphere.inventory.config;

import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.service.ExpireReservationUseCase;
import br.com.hadryan.shosphere.inventory.service.ReservationExpirationJob;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ReservationExpirationConfigTests {
    @Test void enablesActualScheduledExecutionWithConfiguredInterval() {
        var repository = mock(InventoryReservationRepository.class);
        var expire = mock(ExpireReservationUseCase.class);
        var candidate = mock(InventoryReservationRepository.ExpirationCandidate.class);
        var order = UUID.randomUUID();
        when(candidate.getOrderId()).thenReturn(order);
        when(repository.findExpirationCandidates(any(), any(), any())).thenReturn(List.of(candidate));
        var fired = new CountDownLatch(1);
        when(expire.execute(order)).thenAnswer(invocation -> { fired.countDown(); return true; });
        new WebApplicationContextRunner().withUserConfiguration(ReservationExpirationConfig.class)
                .withBean(ReservationExpirationJob.class, () -> new ReservationExpirationJob(repository, expire, 50, "PT0.01S"))
                .withPropertyValues("inventory.reservations.expiration.delay=PT0.01S")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertTrue(fired.await(3, TimeUnit.SECONDS));
                });
    }

    @Test void canDisableSchedulingAndDoesNotStartItInNonWebPersistenceContexts() {
        new WebApplicationContextRunner().withUserConfiguration(ReservationExpirationConfig.class)
                .withPropertyValues("inventory.reservations.expiration.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
        new ApplicationContextRunner().withUserConfiguration(ReservationExpirationConfig.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
}
