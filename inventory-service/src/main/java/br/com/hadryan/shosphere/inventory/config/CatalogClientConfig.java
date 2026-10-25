package br.com.hadryan.shosphere.inventory.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class CatalogClientConfig {
    @Bean(destroyMethod = "close")
    public HttpClient catalogHttpClient(@Value("${services.catalog.connect-timeout:2000}") int connectTimeout) {
        if (connectTimeout <= 0) throw new IllegalArgumentException("Catalog connect timeout must be positive");
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    public RestClient catalogRestClient(@Qualifier("catalogHttpClient") HttpClient httpClient,
                                       @Value("${services.catalog.url}") String url,
                                       @Value("${services.catalog.read-timeout:5000}") int readTimeout) {
        if (readTimeout <= 0) throw new IllegalArgumentException("Catalog read timeout must be positive");
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeout));
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
}
