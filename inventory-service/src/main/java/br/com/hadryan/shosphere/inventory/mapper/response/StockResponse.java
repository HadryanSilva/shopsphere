package br.com.hadryan.shosphere.inventory.mapper.response;

import br.com.hadryan.shosphere.inventory.model.InventoryStock;

import java.time.Instant;
import java.util.UUID;

public record StockResponse(UUID productId, int onHand, int reserved, int available, Long version, Instant updatedAt) {
    public static StockResponse fromStock(InventoryStock stock) {
        return new StockResponse(stock.getProductId(), stock.getOnHand(), stock.getReserved(),
                stock.getAvailable(), stock.getVersion(), stock.getUpdatedAt());
    }
}
