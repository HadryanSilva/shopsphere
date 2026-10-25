package br.com.hadryan.shosphere.order.controller;

import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.service.CreateOrderUseCase;
import br.com.hadryan.shosphere.order.service.GetOrderByIdUseCase;
import br.com.hadryan.shosphere.order.mapper.request.CreateOrderRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final CreateOrderUseCase createOrderUseCase;
    private final GetOrderByIdUseCase getOrderByIdUseCase;

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var customerId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(getOrderByIdUseCase.execute(customerId, id));
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request
    ){
        var customerId = UUID.fromString(jwt.getSubject());
        var response = createOrderUseCase.execute(customerId, idempotencyKey, request);
        if (response == null || response.id() == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Order creation did not return an order");
        }
        return ResponseEntity.created(URI.create("/api/v1/orders/" + response.id())).body(response);
    }

}
