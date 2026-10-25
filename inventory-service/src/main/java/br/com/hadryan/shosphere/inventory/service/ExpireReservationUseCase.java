package br.com.hadryan.shosphere.inventory.service;

import java.util.UUID;

public interface ExpireReservationUseCase {
    boolean execute(UUID orderId);
}
