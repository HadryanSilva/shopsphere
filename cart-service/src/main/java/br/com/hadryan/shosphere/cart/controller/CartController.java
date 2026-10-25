package br.com.hadryan.shosphere.cart.controller;

import br.com.hadryan.shosphere.cart.mapper.request.AddProductRequest;
import br.com.hadryan.shosphere.cart.mapper.request.UpdateCartRequest;
import br.com.hadryan.shosphere.cart.mapper.response.AddedProductResponse;
import br.com.hadryan.shosphere.cart.mapper.response.CartResponse;
import br.com.hadryan.shosphere.cart.service.AddProductToCartUseCase;
import br.com.hadryan.shosphere.cart.service.GetCartUseCase;
import br.com.hadryan.shosphere.cart.service.RemoveCartItemUseCase;
import br.com.hadryan.shosphere.cart.service.UpdateCartUseCase;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/carts")
public class CartController {

    private final AddProductToCartUseCase addProductToCartUseCase;
    private final UpdateCartUseCase updateCartUseCase;
    private final RemoveCartItemUseCase removeCartItemUseCase;
    private final GetCartUseCase getCartUseCase;

    @GetMapping("/me")
    public ResponseEntity<CartResponse> getCart(@AuthenticationPrincipal Jwt jwt) {
        var customerId = UUID.fromString(jwt.getSubject());
        return  ResponseEntity.ok().body(getCartUseCase.execute(customerId));
    }


    @PostMapping("/me/items")
    public ResponseEntity<AddedProductResponse> addProduct(@AuthenticationPrincipal Jwt jwt,
                                                         @Valid @RequestBody AddProductRequest request) {
        var customerId = UUID.fromString(jwt.getSubject());
        var response = addProductToCartUseCase.execute(customerId, request);
        return ResponseEntity.ok()
                .body(response);
    }

    @PatchMapping("/me/items/{productId}")
    public ResponseEntity<Void> updateCart(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable UUID productId,
                                           @Valid @RequestBody UpdateCartRequest request) {
        var customerId = UUID.fromString(jwt.getSubject());
        updateCartUseCase.execute(customerId, productId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me/items/{productId}")
    public ResponseEntity<Void> removeItem(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable UUID productId) {
        var customerId = UUID.fromString(jwt.getSubject());
        removeCartItemUseCase.execute(customerId, productId);
        return ResponseEntity.noContent().build();
    }

}
