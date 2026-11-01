package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.model.PaymentObservation;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.service.impl.PaymentWorkSnapshot;
import org.springframework.http.*;
import org.springframework.web.client.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class PaymentClient {
    private static final String PATH = "/internal/api/v1/payments/{id}";
    private final RestClient client;
    private final CheckoutServiceTokenProvider tokens;
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public PaymentClient(RestClient client, CheckoutServiceTokenProvider tokens) { this.client = client; this.tokens = tokens; }

    public PaymentObservation get(PaymentWorkSnapshot work) {
        return call(() -> client.get().uri(PATH, work.orderId()).header("Authorization", tokens.authorization())
                .retrieve().toEntity(String.class), work, "GET");
    }
    public PaymentObservation capture(PaymentWorkSnapshot work) {
        return call(() -> client.put().uri(PATH, work.orderId()).header("Authorization", tokens.authorization())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", work.amount(), "currency", work.currency()))
                .retrieve().toEntity(String.class), work, "PUT");
    }
    public PaymentObservation cancel(PaymentWorkSnapshot work) {
        return call(() -> client.post().uri(PATH + "/cancel", work.orderId()).header("Authorization", tokens.authorization())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", work.amount(), "currency", work.currency()))
                .retrieve().toEntity(String.class), work, "CANCEL");
    }
    private PaymentObservation call(Supplier<ResponseEntity<String>> operation, PaymentWorkSnapshot work, String method) {
        try {
            var response = operation.get();
            var code = response.getStatusCode().value();
            if (response.getBody() == null || response.getHeaders().getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType())) throw invalid();
            var proof = decode(response.getBody(), work);
            boolean validStatus = switch (method) {
                case "GET" -> code == 200;
                case "PUT" -> code == 202 && proof.status() == OrderPaymentStatus.PROCESSING
                        || (code == 200 || code == 201) && (proof.status() == OrderPaymentStatus.CAPTURED || proof.status() == OrderPaymentStatus.DECLINED);
                default -> code == 202 && proof.status() == OrderPaymentStatus.REFUND_PENDING
                        || code == 200 && (proof.status() == OrderPaymentStatus.CANCELLED || proof.status() == OrderPaymentStatus.DECLINED
                            || proof.status() == OrderPaymentStatus.REFUNDED);
            };
            if (!validStatus) throw invalid();
            return proof;
        } catch (CheckoutIntegrationFailure failure) {
            throw new PaymentIntegrationFailure(0, failure.authenticationConfiguration()
                    ? PaymentFailureCategory.AUTHENTICATION_CONFIGURATION : PaymentFailureCategory.UPSTREAM_UNAVAILABLE);
        } catch (ResourceAccessException failure) {
            throw new PaymentIntegrationFailure(0, PaymentFailureCategory.UPSTREAM_UNAVAILABLE);
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (status == 401 || status == 403) tokens.invalidate();
            throw new PaymentIntegrationFailure(status, status == 401 || status == 403
                    ? PaymentFailureCategory.AUTHENTICATION_CONFIGURATION : status >= 500
                    ? PaymentFailureCategory.UPSTREAM_UNAVAILABLE : PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE);
        } catch (RestClientException failure) { throw invalid(); }
    }
    private PaymentObservation decode(String body, PaymentWorkSnapshot work) {
        try {
            var json = mapper.readTree(body); var amount = json.get("amount");
            if (amount == null || !amount.isNumber()) throw invalid();
            var proof = new PaymentObservation(UUID.fromString(text(json, "orderId")), OrderPaymentStatus.valueOf(text(json, "status")),
                    amount.decimalValue(), text(json, "currency"), Instant.parse(text(json, "createdAt")),
                    Instant.parse(text(json, "updatedAt")), nullableInstant(json, "capturedAt"), nullableInstant(json, "refundedAt"));
            if (!proof.validFor(work.orderId(), work.amount(), work.currency())) throw invalid();
            return proof;
        } catch (RuntimeException failure) { throw invalid(); }
    }
    private String text(JsonNode json, String field) {
        var node = json == null ? null : json.get(field);
        if (node == null || !node.isString()) throw invalid();
        return node.asString();
    }
    private Instant nullableInstant(JsonNode json, String field) {
        if (!json.has(field)) throw invalid();
        return json.get(field).isNull() ? null : Instant.parse(text(json, field));
    }
    private PaymentIntegrationFailure invalid() { return new PaymentIntegrationFailure(0, PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE); }
}
