package br.com.hadryan.shopsphere.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.HandlerFunction;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;
import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RequestPredicates.POST;

@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> gatewayRoutes(
            @Value("${services.catalog.url}") String catalogUrl,
            @Value("${services.cart.url}") String cartUrl,
            @Value("${services.order.url}") String orderUrl,
            @Value("${services.inventory.url}") String inventoryUrl,
            @Qualifier("inventoryHandler") HandlerFunction<ServerResponse> inventoryHandler) {

        return route("catalog-service")
                .route(path("/api/v1/products/**").or(path("/api/v1/categories/**")), http())
                .before(uri(catalogUrl))
                .build()

                .and(route("cart-service")
                        .route(path("/api/v1/carts/**"), http())
                        .before(uri(cartUrl))
                        .build())

                .and(route("order-service")
                        .route(path("/api/v1/orders/**"), http())
                        .before(uri(orderUrl))
                        .build())

                .and(route("inventory-service")
                        .route(GET("/api/v1/inventory/items/batch")
                                .or(GET("/api/v1/inventory/items/{productId}"))
                                .or(GET("/api/v1/inventory/items/{productId}/adjustments/{id}"))
                                .or(POST("/api/v1/inventory/items"))
                                .or(POST("/api/v1/inventory/items/{productId}/adjustments")), inventoryHandler)
                        .before(uri(inventoryUrl))
                        .build());
    }
}
