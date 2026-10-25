package br.com.hadryan.shopsphere.gateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.http.HttpClient;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class InventoryProxyConfigTests {
    @ParameterizedTest @ValueSource(longs = {0, -1})
    void rejectsNonPositiveConnectTimeout(long timeout) {
        assertThrows(IllegalArgumentException.class, () -> new InventoryProxyConfig().inventoryHttpClient(timeout));
    }

    @ParameterizedTest @ValueSource(longs = {0, -1})
    void rejectsNonPositiveReadTimeout(long timeout) {
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryProxyConfig().inventoryHandler(null, timeout, null, null, null, null));
    }

    @Test void clientUsesConfiguredConnectionDeadlineAndDoesNotFollowRedirects() {
        try (var client = new InventoryProxyConfig().inventoryHttpClient(2000)) {
            assertEquals(Duration.ofSeconds(2), client.connectTimeout().orElseThrow());
            assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
        }
    }
}
