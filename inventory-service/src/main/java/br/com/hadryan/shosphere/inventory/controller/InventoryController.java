package br.com.hadryan.shosphere.inventory.controller;

import br.com.hadryan.shosphere.inventory.mapper.request.RegisterStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.StockResponse;
import br.com.hadryan.shosphere.inventory.service.RegisterStockUseCase;
import br.com.hadryan.shosphere.inventory.service.AdjustStockUseCase;
import br.com.hadryan.shosphere.inventory.service.GetStockAdjustmentUseCase;
import br.com.hadryan.shosphere.inventory.mapper.request.AdjustStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.AdjustmentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/inventory/items")
public class InventoryController {
    private final RegisterStockUseCase registerStock;
    private final AdjustStockUseCase adjustStock;
    private final GetStockAdjustmentUseCase getAdjustment;

    @PostMapping
    public ResponseEntity<StockResponse> register(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody RegisterStockRequest request) {
        var actor = authenticatedActor(jwt);
        var response = registerStock.execute(actor, key, request);
        if (response == null || response.productId() == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Registration did not return stock");
        }
        return ResponseEntity.created(URI.create("/api/v1/inventory/items/" + response.productId())).body(response);
    }

    @PostMapping("/{productId}/adjustments")
    public ResponseEntity<AdjustmentResponse> adjust(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody AdjustStockRequest request) {
        var actor = authenticatedActor(jwt);
        if (request.delta() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adjustment delta must not be zero");
        }
        var response = adjustStock.execute(actor, key, productId, request);
        if (response == null || response.id() == null || response.productId() == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Adjustment did not return a movement");
        }
        return ResponseEntity.created(URI.create("/api/v1/inventory/items/" + response.productId()
                + "/adjustments/" + response.id())).body(response);
    }

    @GetMapping("/{productId}/adjustments/{id}")
    public ResponseEntity<AdjustmentResponse> getAdjustment(@PathVariable UUID productId, @PathVariable UUID id) {
        return ResponseEntity.ok(getAdjustment.execute(productId, id));
    }

    private String authenticatedActor(Jwt jwt) {
        var actor = jwt.getSubject();
        if (actor == null || actor.isBlank() || actor.length() > 255) {
            throw new BadCredentialsException("Invalid authenticated subject");
        }
        return actor;
    }
}
