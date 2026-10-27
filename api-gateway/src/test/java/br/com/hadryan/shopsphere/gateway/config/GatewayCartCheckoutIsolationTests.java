package br.com.hadryan.shopsphere.gateway.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.ServletRequestPathUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GatewayCartCheckoutIsolationTests {
    @ParameterizedTest
    @CsvSource({"PUT,/internal/api/v1/cart-checkouts/123", "GET,/internal/api/v1/cart-checkouts/123",
            "POST,/internal/api/v1/cart-checkouts/123/complete", "POST,/internal/api/v1/cart-checkouts/123/abort"})
    void internalCheckoutOperationsHaveNoGatewayRoute(String method, String path) {
        var routes = new GatewayRoutesConfig().gatewayRoutes("http://catalog.invalid", "http://cart.invalid",
                "http://order.invalid", "http://inventory.invalid", request -> ServerResponse.noContent().build());
        var servlet = new MockHttpServletRequest(method, path);
        ServletRequestPathUtils.parseAndCache(servlet);
        assertFalse(routes.route(ServerRequest.create(servlet, List.of())).isPresent());
    }
}
