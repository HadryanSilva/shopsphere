package br.com.hadryan.shopsphere.gateway.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.ServletRequestPathUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GatewayInventoryRoutePredicateTests {
    @ParameterizedTest
    @CsvSource({"GET,/api/v1/inventory/items/batch,true", "GET,/api/v1/inventory/items/123,true",
            "GET,/api/v1/inventory/items/123/adjustments/456,true", "POST,/api/v1/inventory/items,true",
            "POST,/api/v1/inventory/items/123/adjustments,true", "GET,/internal/api/v1/inventory/reservations/123,false",
            "PUT,/internal/api/v1/inventory/reservations/123,false", "POST,/internal/api/v1/inventory/reservations/123/confirm,false",
            "POST,/internal/api/v1/inventory/reservations/123/release,false", "GET,/api/v1/inventory/reservations/123,false",
            "DELETE,/api/v1/inventory/items/123,false", "PATCH,/api/v1/inventory/items/123,false",
            "POST,/api/v1/inventory/items/batch,false", "GET,/api/v1/inventory/items/123/adjustments,false",
            "POST,/api/v1/inventory/items/123/reserve,false", "GET,/api/v1/inventory/items,false"})
    void routeItselfIncludesOnlyApprovedInventoryOperations(String method, String path, boolean expected) {
        var routes = new GatewayRoutesConfig().gatewayRoutes("http://catalog.invalid", "http://cart.invalid",
                "http://order.invalid", "http://inventory.invalid", request -> ServerResponse.noContent().build());
        var servlet = new MockHttpServletRequest(method, path);
        ServletRequestPathUtils.parseAndCache(servlet);
        assertEquals(expected, routes.route(ServerRequest.create(servlet, List.of())).isPresent());
    }
}
