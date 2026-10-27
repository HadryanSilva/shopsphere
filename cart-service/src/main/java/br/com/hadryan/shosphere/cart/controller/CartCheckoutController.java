package br.com.hadryan.shosphere.cart.controller;

import br.com.hadryan.shosphere.cart.mapper.request.CheckoutCompleteRequest;
import br.com.hadryan.shosphere.cart.mapper.request.CheckoutCustomerRequest;
import br.com.hadryan.shosphere.cart.mapper.response.CartCheckoutResponse;
import br.com.hadryan.shosphere.cart.service.impl.CartCheckoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/api/v1/cart-checkouts")
public class CartCheckoutController {
    private final CartCheckoutService checkouts;

    @PutMapping("/{checkoutId}")
    public ResponseEntity<CartCheckoutResponse> prepare(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID checkoutId,
                                                       @Valid @RequestBody CheckoutCustomerRequest request) {
        var result = checkouts.prepare(checkoutId, request.customerId(), jwt.getClaimAsString("azp"));
        return ResponseEntity.status(result.created() ? 201 : 200)
                .location(URI.create("/internal/api/v1/cart-checkouts/" + checkoutId))
                .body(CartCheckoutResponse.from(result.snapshot()));
    }

    @GetMapping("/{checkoutId}")
    public ResponseEntity<CartCheckoutResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID checkoutId) {
        return ResponseEntity.ok(CartCheckoutResponse.from(checkouts.get(checkoutId, jwt.getClaimAsString("azp"))));
    }

    @PostMapping("/{checkoutId}/complete")
    public ResponseEntity<CartCheckoutResponse> complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID checkoutId,
                                                        @Valid @RequestBody CheckoutCompleteRequest request) {
        return ResponseEntity.ok(CartCheckoutResponse.from(
                checkouts.complete(checkoutId, request.orderId(), jwt.getClaimAsString("azp"))));
    }

    @PostMapping("/{checkoutId}/abort")
    public ResponseEntity<CartCheckoutResponse> abort(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID checkoutId,
                                                     @Valid @RequestBody CheckoutCustomerRequest request) {
        return ResponseEntity.ok(CartCheckoutResponse.from(
                checkouts.abort(checkoutId, request.customerId(), jwt.getClaimAsString("azp"))));
    }
}
