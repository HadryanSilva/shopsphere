package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.mapper.response.CartResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

@FeignClient(
        name = "cart-service",
        url = "${services.cart.url}",
        path = "/api/v1/carts"
)
public interface CartClient {

    @GetMapping("/me")
    CartResponse getCustomerCart(@RequestHeader("Authorization") String authorization);

}
