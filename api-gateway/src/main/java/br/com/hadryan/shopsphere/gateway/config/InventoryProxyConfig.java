package br.com.hadryan.shopsphere.gateway.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.ResponseHttpHeadersFilter;
import org.springframework.cloud.gateway.server.mvc.handler.ProxyExchangeHandlerFunction;
import org.springframework.cloud.gateway.server.mvc.handler.RestClientProxyExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.ServerRequest;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration
public class InventoryProxyConfig {
    @Bean(destroyMethod = "close")
    HttpClient inventoryHttpClient(@Value("${services.inventory.connect-timeout:2000}") long connectTimeout) {
        if (connectTimeout <= 0) throw new IllegalArgumentException("Inventory connect timeout must be positive");
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    InventoryHandler inventoryHandler(@Qualifier("inventoryHttpClient") HttpClient client,
            @Value("${services.inventory.read-timeout:5000}") long readTimeout, RestClient.Builder builder,
            GatewayMvcProperties properties, ObjectProvider<RequestHttpHeadersFilter> requestFilters,
            ObjectProvider<ResponseHttpHeadersFilter> responseFilters) {
        if (readTimeout <= 0) throw new IllegalArgumentException("Inventory read timeout must be positive");
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(readTimeout));
        var exchange = new RestClientProxyExchange(builder.clone().requestFactory(factory).build(), properties);
        var proxy = new ProxyExchangeHandlerFunction(exchange, requestFilters, responseFilters);
        return new InventoryHandler(proxy);
    }

    static class InventoryHandler implements HandlerFunction<ServerResponse>, ApplicationListener<ContextRefreshedEvent> {
        private final ProxyExchangeHandlerFunction proxy;

        InventoryHandler(ProxyExchangeHandlerFunction proxy) { this.proxy = proxy; }

        @Override
        public void onApplicationEvent(ContextRefreshedEvent event) { proxy.onApplicationEvent(event); }

        @Override
        public ServerResponse handle(ServerRequest request) {
            try {
                return proxy.handle(request);
            } catch (ResourceAccessException failure) {
                return ServerResponse.status(503).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(Map.of("type", "about:blank", "title", "Service Unavailable", "status", 503,
                                "detail", "Inventory is unavailable.", "instance", request.path(), "errors", List.of()));
            }
        }
    }
}
