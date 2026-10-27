package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.client.CartCheckoutClient;
import br.com.hadryan.shosphere.order.client.CartClient;
import br.com.hadryan.shosphere.order.client.CatalogClient;
import br.com.hadryan.shosphere.order.service.CreateOrderUseCase;
import br.com.hadryan.shosphere.order.util.CurrentUserTokenProvider;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CheckoutModeTests {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(CreateOrderUseCaseImpl.class, SnapshotCheckoutUseCase.class)
                .withBean(CurrentUserTokenProvider.class, () -> mock(CurrentUserTokenProvider.class))
                .withBean(CartClient.class, () -> mock(CartClient.class))
                .withBean(CatalogClient.class, () -> mock(CatalogClient.class))
                .withBean(CreateOrderTransaction.class, () -> mock(CreateOrderTransaction.class))
                .withBean(OrderIdempotency.class, () -> mock(OrderIdempotency.class))
                .withBean(Validator.class, () -> mock(Validator.class))
                .withBean(CheckoutIntentService.class, () -> mock(CheckoutIntentService.class))
                .withInitializer(context -> context.getBeanFactory().registerSingleton("checkoutIntentTransaction", mock(CheckoutIntentTransaction.class)))
                .withBean(CartCheckoutClient.class, () -> mock(CartCheckoutClient.class));
    }
    @Test
    void onlyLegacyCheckoutIsSelectedByDefault() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(CreateOrderUseCase.class);
            assertThat(context.getBean(CreateOrderUseCase.class)).isInstanceOf(CreateOrderUseCaseImpl.class);
        });
    }
    @Test
    void explicitActivationSelectsOnlySnapshotCheckout() {
        runner().withPropertyValues("checkout.snapshot.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(CreateOrderUseCase.class);
            assertThat(context.getBean(CreateOrderUseCase.class)).isInstanceOf(SnapshotCheckoutUseCase.class);
        });
    }
}
