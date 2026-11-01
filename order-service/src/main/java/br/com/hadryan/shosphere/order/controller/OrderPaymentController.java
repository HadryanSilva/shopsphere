package br.com.hadryan.shosphere.order.controller;

import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.service.impl.OrderPaymentTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderPaymentController {
    private final OrderPaymentTransaction payment;

    @PutMapping("/{id}/payment")
    public ResponseEntity<OrderResponse> request(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                @RequestBody(required = false) JsonNode body) {
        if (body != null && (!body.isObject() || !body.isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment request must be empty");
        var result = payment.request(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.status(result.terminal() ? 200 : 202)
                .location(URI.create("/api/v1/orders/" + id)).body(result.order());
    }
}
