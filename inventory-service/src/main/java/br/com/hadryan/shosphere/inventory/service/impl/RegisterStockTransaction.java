package br.com.hadryan.shosphere.inventory.service.impl;

import br.com.hadryan.shosphere.inventory.mapper.request.RegisterStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.StockResponse;
import br.com.hadryan.shosphere.inventory.model.InventoryMovement;
import br.com.hadryan.shosphere.inventory.model.InventoryStock;
import br.com.hadryan.shosphere.inventory.repository.InventoryMovementRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RegisterStockTransaction {
    private final InventoryStockRepository stocks;
    private final InventoryMovementRepository movements;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StockResponse execute(String actor, UUID key, RegisterStockRequest request) {
        var stock = stocks.saveAndFlush(new InventoryStock(request.productId(), request.onHand()));
        movements.saveAndFlush(InventoryMovement.initial(stock, actor, key));
        return StockResponse.fromStock(stock);
    }
}
