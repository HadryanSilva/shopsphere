package br.com.hadryan.shosphere.inventory.controller;

import br.com.hadryan.shosphere.inventory.mapper.response.StockResponse;
import br.com.hadryan.shosphere.inventory.service.GetStockUseCase;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/inventory/items")
public class InventoryQueryController {
    private final GetStockUseCase getStock;

    @GetMapping("/{productId}")
    public ResponseEntity<StockResponse> get(@PathVariable UUID productId) {
        return ResponseEntity.ok(getStock.execute(productId));
    }

    @GetMapping("/batch")
    public ResponseEntity<List<StockResponse>> batch(
            @RequestParam @Size(min = 1, max = 100) List<@NotNull UUID> productIds) {
        return ResponseEntity.ok(getStock.executeBatch(productIds));
    }
}
