package br.com.hadryan.shosphere.inventory.controller;

import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;
import br.com.hadryan.shosphere.inventory.service.GetReservationUseCase;
import br.com.hadryan.shosphere.inventory.service.ReserveStockUseCase;
import br.com.hadryan.shosphere.inventory.service.TransitionReservationUseCase;
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
@RequestMapping("/internal/api/v1/inventory/reservations")
public class InventoryReservationController {
    private final ReserveStockUseCase reserve;
    private final GetReservationUseCase getReservation;
    private final TransitionReservationUseCase transition;

    @PutMapping("/{orderId}")
    public ResponseEntity<ReservationResponse> reserve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
                                                     @Valid @RequestBody ReserveStockRequest request) {
        var result = reserve.execute(serviceClient(jwt), orderId, request);
        return ResponseEntity.status(result.created() ? 201 : 200)
                .location(URI.create("/internal/api/v1/inventory/reservations/" + orderId)).body(result.reservation());
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ReservationResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return ResponseEntity.ok(getReservation.execute(serviceClient(jwt), orderId));
    }

    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<ReservationResponse> confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return ResponseEntity.ok(transition.confirm(serviceClient(jwt), orderId));
    }

    @PostMapping("/{orderId}/release")
    public ResponseEntity<ReservationResponse> release(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return ResponseEntity.ok(transition.release(serviceClient(jwt), orderId));
    }

    private String serviceClient(Jwt jwt) {
        String client = jwt.getClaimAsString("azp");
        if (client == null || client.isBlank() || client.length() > 255) {
            throw new BadCredentialsException("Invalid service client");
        }
        return client;
    }
}
