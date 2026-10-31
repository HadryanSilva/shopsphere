package br.com.hadryan.shosphere.order.config;

import br.com.hadryan.shosphere.order.client.CheckoutServiceTokenProvider;
import br.com.hadryan.shosphere.order.client.InventoryReservationClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "checkout.snapshot.enabled", havingValue = "true")
public class InventoryClientConfig {
    @Bean(destroyMethod = "close")
    HttpClient inventoryHttpClient(@Value("${stock.connect-timeout:2000}") int timeout) {
        Assert.isTrue(timeout > 0, "Inventory connect timeout must be positive");
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    InventoryReservationClient inventoryReservationClient(CheckoutServiceTokenProvider tokens,
            @org.springframework.beans.factory.annotation.Qualifier("inventoryHttpClient") HttpClient client,
            @Value("${services.inventory.url:http://localhost:8084}") String url,
            @Value("${stock.read-timeout:5000}") int timeout) {
        var uri = URI.create(url);
        Assert.isTrue(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                && (uri.getPath().isEmpty() || uri.getPath().equals("/")), "Valid Inventory base URL is required");
        Assert.isTrue(timeout > 0, "Inventory read timeout must be positive");
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return new InventoryReservationClient(RestClient.builder().baseUrl(url).requestFactory(factory).build(), tokens);
    }
}
