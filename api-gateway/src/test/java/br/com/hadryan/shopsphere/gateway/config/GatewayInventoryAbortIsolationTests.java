package br.com.hadryan.shopsphere.gateway.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.ServletRequestPathUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GatewayInventoryAbortIsolationTests {
    @ParameterizedTest @ValueSource(strings = {"POST", "GET", "PUT", "PATCH", "DELETE"})
    void internalAbortHasNoGatewayRoute(String method) {
        var routes = new GatewayRoutesConfig().gatewayRoutes("http://catalog.invalid", "http://cart.invalid",
                "http://order.invalid", "http://inventory.invalid", request -> ServerResponse.noContent().build());
        var servlet = new MockHttpServletRequest(method, "/internal/api/v1/inventory/reservations/123/abort");
        ServletRequestPathUtils.parseAndCache(servlet);
        assertFalse(routes.route(ServerRequest.create(servlet, List.of())).isPresent());
    }
}
