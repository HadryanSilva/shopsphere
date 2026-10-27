package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.mapper.response.CartCheckoutResponse;
import br.com.hadryan.shosphere.order.model.enums.CheckoutRetryCategory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class CartCheckoutClient {
    private final RestClient client;
    private final CheckoutServiceTokenProvider tokens;
    private static final String PATH = "/internal/api/v1/cart-checkouts/{id}";

    public CartCheckoutClient(RestClient client, CheckoutServiceTokenProvider tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    public CartCheckoutResponse prepare(UUID id, UUID customerId) {
        return call(() -> client.put().uri(PATH, id).header("Authorization", tokens.authorization())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("customerId", customerId))
                .retrieve().body(CartCheckoutResponse.class), true);
    }
    public CartCheckoutResponse get(UUID id) {
        return call(() -> client.get().uri(PATH, id).header("Authorization", tokens.authorization())
                .retrieve().body(CartCheckoutResponse.class), false);
    }
    public CartCheckoutResponse complete(UUID id, UUID orderId) {
        return call(() -> client.post().uri(PATH + "/complete", id).header("Authorization", tokens.authorization())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("orderId", orderId))
                .retrieve().body(CartCheckoutResponse.class), false);
    }
    public CartCheckoutResponse abort(UUID id, UUID customerId) {
        return call(() -> client.post().uri(PATH + "/abort", id).header("Authorization", tokens.authorization())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("customerId", customerId))
                .retrieve().body(CartCheckoutResponse.class), false);
    }

    private CartCheckoutResponse call(Supplier<CartCheckoutResponse> request, boolean preparing) {
        try { return request.get(); }
        catch (ResourceAccessException failure) {
            throw new CheckoutIntegrationFailure(503, CheckoutRetryCategory.UPSTREAM_UNAVAILABLE);
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (status == 401 || status == 403) tokens.invalidate();
            if (preparing && (status == 404 || status == 409))
                throw new ResponseStatusException(HttpStatus.valueOf(status), "Cart checkout unavailable");
            throw new CheckoutIntegrationFailure(502, CheckoutRetryCategory.UPSTREAM_REJECTED);
        } catch (RestClientException failure) {
            throw new CheckoutIntegrationFailure(502, CheckoutRetryCategory.INVALID_UPSTREAM_RESPONSE);
        }
    }
}
