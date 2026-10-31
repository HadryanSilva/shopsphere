package br.com.hadryan.shosphere.inventory.mapper.response;

import br.com.hadryan.shosphere.inventory.service.AbortReservationUseCase;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AbortReservationResponse(UUID orderId, String status, ReservationResponse reservation) {
    public static AbortReservationResponse from(AbortReservationUseCase.Result result) {
        return new AbortReservationResponse(result.orderId(), "ABORTED", result.reservation());
    }
}
