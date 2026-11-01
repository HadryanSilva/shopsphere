package br.com.hadryan.shosphere.payment.controller;

import br.com.hadryan.shosphere.payment.mapper.request.PaymentRequest;
import br.com.hadryan.shosphere.payment.mapper.response.PaymentResponse;
import br.com.hadryan.shosphere.payment.model.PaymentStatus;
import br.com.hadryan.shosphere.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/api/v1/payments")
public class PaymentController {
    private final PaymentService payments;

    @PutMapping("/{orderId}")
    public ResponseEntity<PaymentResponse> capture(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
                                                  @Valid @RequestBody PaymentRequest request) {
        var result = payments.capture(serviceClient(jwt), orderId, request);
        return ResponseEntity.status(result.payment().status() == PaymentStatus.PROCESSING ? 202 : result.created() ? 201 : 200)
                .location(URI.create("/internal/api/v1/payments/" + orderId)).body(result.payment());
    }
    @GetMapping("/{orderId}")
    public PaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return payments.get(serviceClient(jwt), orderId);
    }
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<PaymentResponse> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
                                                 @Valid @RequestBody PaymentRequest request) {
        var result = payments.cancel(serviceClient(jwt), orderId, request);
        return ResponseEntity.status(result.status() == PaymentStatus.REFUND_PENDING ? 202 : 200).body(result);
    }
    private String serviceClient(Jwt jwt) {
        var client = jwt.getClaimAsString("azp");
        if (client == null || client.isBlank() || client.length() > 255) throw new BadCredentialsException("Invalid service client");
        return client;
    }
}
