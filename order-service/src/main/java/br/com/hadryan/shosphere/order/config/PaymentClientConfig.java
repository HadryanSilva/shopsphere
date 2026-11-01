package br.com.hadryan.shosphere.order.config;

import br.com.hadryan.shosphere.order.client.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "checkout.snapshot.enabled", havingValue = "true")
public class PaymentClientConfig {
    @Bean(destroyMethod = "close")
    HttpClient paymentHttpClient(@Value("${payment.connect-timeout:2000}") int timeout) {
        Assert.isTrue(timeout > 0, "Payment connect timeout must be positive");
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Bean
    PaymentClient paymentClient(CheckoutServiceTokenProvider tokens, @Qualifier("paymentHttpClient") HttpClient client,
            @Value("${services.payment.url:http://localhost:8085}") String url, @Value("${payment.read-timeout:5000}") int timeout) {
        var uri = URI.create(url);
        Assert.isTrue(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                && (uri.getPath().isEmpty() || uri.getPath().equals("/")), "Valid Payment base URL is required");
        Assert.isTrue(timeout > 0, "Payment read timeout must be positive");
        var factory = new JdkClientHttpRequestFactory(client); factory.setReadTimeout(Duration.ofMillis(timeout));
        return new PaymentClient(RestClient.builder().baseUrl(url).requestFactory(factory).build(), tokens);
    }
}
