package br.com.hadryan.shosphere.order.config;

import br.com.hadryan.shosphere.order.client.CartCheckoutClient;
import br.com.hadryan.shosphere.order.client.CheckoutServiceTokenProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class CartCheckoutClientConfig {
    @Bean(destroyMethod = "close")
    HttpClient checkoutHttpClient(@Value("${checkout.snapshot.connect-timeout:2000}") int timeout) {
        Assert.isTrue(timeout > 0, "Checkout connect timeout must be positive");
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Bean
    CheckoutServiceTokenProvider checkoutServiceTokenProvider(@Qualifier("checkoutHttpClient") HttpClient client,
            @Value("${checkout.snapshot.token-uri}") String uri,
            @Value("${checkout.snapshot.client-id:shopsphere-order}") String clientId,
            @Value("${checkout.snapshot.client-secret}") String secret,
            @Value("${checkout.snapshot.read-timeout:5000}") int timeout) {
        var tokenUri = URI.create(uri);
        Assert.isTrue(("http".equals(tokenUri.getScheme()) || "https".equals(tokenUri.getScheme()))
                && tokenUri.getHost() != null && tokenUri.getUserInfo() == null, "Valid checkout token URI is required");
        Assert.isTrue("shopsphere-order".equals(clientId), "Checkout client must be shopsphere-order");
        Assert.hasText(secret, "Checkout client secret must be configured");
        return new CheckoutServiceTokenProvider(restClient(client, timeout), uri, clientId, secret);
    }
    @Bean
    CartCheckoutClient cartCheckoutClient(@Qualifier("checkoutHttpClient") HttpClient client,
            CheckoutServiceTokenProvider tokens, @Value("${services.cart.url}") String url,
            @Value("${checkout.snapshot.read-timeout:5000}") int timeout) {
        return new CartCheckoutClient(restClient(client, timeout).mutate().baseUrl(url).build(), tokens);
    }
    private RestClient restClient(HttpClient client, int timeout) {
        Assert.isTrue(timeout > 0, "Checkout read timeout must be positive");
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return RestClient.builder().requestFactory(factory).build();
    }
}
