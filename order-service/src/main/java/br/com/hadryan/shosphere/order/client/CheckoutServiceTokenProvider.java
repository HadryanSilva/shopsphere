package br.com.hadryan.shosphere.order.client;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import br.com.hadryan.shosphere.order.model.enums.CheckoutRetryCategory;
import java.time.Instant;

public class CheckoutServiceTokenProvider {
    private final RestClient client;
    private final String tokenUri;
    private final String clientId;
    private final String secret;
    private String token;
    private Instant refreshAt = Instant.EPOCH;

    public CheckoutServiceTokenProvider(RestClient client, String tokenUri, String clientId, String secret) {
        this.client = client;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.secret = secret;
    }

    public synchronized String authorization() {
        if (token != null && Instant.now().isBefore(refreshAt)) return "Bearer " + token;
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", secret);
        try {
            var started = Instant.now();
            var result = client.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(TokenResponse.class);
            if (result == null || result.access_token() == null || result.access_token().isBlank()
                    || !"Bearer".equalsIgnoreCase(result.token_type()) || result.expires_in() == null
                    || result.expires_in() <= 0 || result.expires_in() > 86400)
                throw new CheckoutIntegrationFailure(502, CheckoutRetryCategory.INVALID_UPSTREAM_RESPONSE);
            token = result.access_token();
            refreshAt = started.plusSeconds(Math.max(0, result.expires_in() - 5));
            return "Bearer " + token;
        } catch (ResourceAccessException failure) {
            throw new CheckoutIntegrationFailure(503, CheckoutRetryCategory.UPSTREAM_UNAVAILABLE);
        } catch (RestClientException failure) {
            throw new CheckoutIntegrationFailure(502, CheckoutRetryCategory.UPSTREAM_REJECTED);
        }
    }

    public synchronized void invalidate() { token = null; refreshAt = Instant.EPOCH; }

    private record TokenResponse(String access_token, String token_type, Long expires_in) {}
}
