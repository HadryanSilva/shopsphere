package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.service.impl.StockReservationObservation;
import br.com.hadryan.shosphere.order.service.impl.StockWorkSnapshot;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.*;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public class InventoryReservationClient {
    private static final String PATH = "/internal/api/v1/inventory/reservations/{id}";
    private final RestClient client;
    private final CheckoutServiceTokenProvider tokens;

    public InventoryReservationClient(RestClient client, CheckoutServiceTokenProvider tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    public StockReservationObservation get(StockWorkSnapshot work) {
        return reservation(call(() -> client.get().uri(PATH, work.orderId()).header("Authorization", tokens.authorization())
                .retrieve().toEntity(JsonNode.class), false), work);
    }

    public StockReservationObservation reserve(StockWorkSnapshot work) {
        return reservation(call(() -> client.put().uri(PATH, work.orderId()).header("Authorization", tokens.authorization())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("items", work.items()))
                .retrieve().toEntity(JsonNode.class), true), work);
    }

    public StockReservationObservation confirm(StockWorkSnapshot work) {
        var proof = reservation(call(() -> client.post().uri(PATH + "/confirm", work.orderId())
                .header("Authorization", tokens.authorization()).retrieve().toEntity(JsonNode.class), false), work);
        if (proof.status() != StockReservationStatus.CONFIRMED) throw invalid();
        return proof;
    }

    public AbortProof abort(StockWorkSnapshot work) {
        var json = call(() -> client.post().uri(PATH + "/abort", work.orderId()).header("Authorization", tokens.authorization())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("items", work.items()))
                .retrieve().toEntity(JsonNode.class), false);
        try {
            if (!work.orderId().equals(UUID.fromString(text(json, "orderId"))) || !"ABORTED".equals(text(json, "status"))
                    || !json.has("reservation")) throw invalid();
            var nested = json.get("reservation");
            var reservation = nested.isNull() ? null : reservation(nested, work);
            if (reservation != null && reservation.status() != StockReservationStatus.RELEASED
                    && reservation.status() != StockReservationStatus.EXPIRED) throw invalid();
            return new AbortProof(work.orderId(), reservation);
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    public record AbortProof(UUID orderId, StockReservationObservation reservation) {}

    private JsonNode call(Supplier<ResponseEntity<JsonNode>> operation, boolean reserving) {
        try {
            var result = operation.get();
            int status = result.getStatusCode().value();
            if (status != 200 && !(reserving && status == 201) || result.getBody() == null) throw invalid();
            return result.getBody();
        } catch (CheckoutIntegrationFailure failure) {
            throw new StockIntegrationFailure(0, failure.authenticationConfiguration()
                    ? StockFailureCategory.AUTHENTICATION_CONFIGURATION : StockFailureCategory.UPSTREAM_UNAVAILABLE);
        } catch (ResourceAccessException failure) {
            throw new StockIntegrationFailure(0, StockFailureCategory.UPSTREAM_UNAVAILABLE);
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (status == 401 || status == 403) tokens.invalidate();
            throw new StockIntegrationFailure(status, status == 401 || status == 403
                    ? StockFailureCategory.AUTHENTICATION_CONFIGURATION : status >= 500
                    ? StockFailureCategory.UPSTREAM_UNAVAILABLE : StockFailureCategory.INVALID_UPSTREAM_RESPONSE);
        } catch (RestClientException failure) { throw invalid(); }
    }

    private StockReservationObservation reservation(JsonNode json, StockWorkSnapshot work) {
        try {
            var id = UUID.fromString(text(json, "orderId"));
            var status = StockReservationStatus.valueOf(text(json, "status"));
            var created = Instant.parse(text(json, "createdAt"));
            var expires = Instant.parse(text(json, "expiresAt"));
            var updated = Instant.parse(text(json, "updatedAt"));
            var nodes = json.get("items");
            if (!id.equals(work.orderId()) || !created.isBefore(expires) || updated.isBefore(created)
                    || nodes == null || !nodes.isArray() || nodes.isEmpty() || nodes.size() > 100
                    || work.reservationCreatedAt() != null && (!created.equals(work.reservationCreatedAt())
                        || !expires.equals(work.reservationExpiresAt()))) throw invalid();
            Map<UUID, Integer> received = new HashMap<>(), expected = new HashMap<>();
            List<StockWorkSnapshot.Item> items = new ArrayList<>();
            for (var item : work.items()) expected.put(item.productId(), item.quantity());
            for (var node : nodes) {
                var product = UUID.fromString(text(node, "productId"));
                var quantity = node.get("quantity");
                if (quantity == null || !quantity.isIntegralNumber() || !quantity.canConvertToInt() || quantity.intValue() <= 0
                        || received.putIfAbsent(product, quantity.intValue()) != null) throw invalid();
                items.add(new StockWorkSnapshot.Item(product, quantity.intValue()));
            }
            if (!received.equals(expected)) throw invalid();
            return new StockReservationObservation(id, status, List.copyOf(items), created, expires, updated);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException failure) { throw invalid(); }
    }

    private String text(JsonNode json, String name) {
        var value = json == null ? null : json.get(name);
        if (value == null || !value.isString()) throw invalid();
        return value.asString();
    }

    private StockIntegrationFailure invalid() { return new StockIntegrationFailure(0, StockFailureCategory.INVALID_UPSTREAM_RESPONSE); }
}
