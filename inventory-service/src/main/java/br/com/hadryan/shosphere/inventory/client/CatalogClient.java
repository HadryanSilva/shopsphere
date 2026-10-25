package br.com.hadryan.shosphere.inventory.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Component
public class CatalogClient {
    private final RestClient restClient;

    public CatalogClient(@Qualifier("catalogRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public void requireProduct(UUID productId) {
        try {
            var response = restClient.get().uri("/api/v1/products/{id}", productId).retrieve().toEntity(ProductIdentity.class);
            if (response.getStatusCode().value() != 200 || response.getBody() == null
                    || !productId.equals(response.getBody().id())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid catalog service response");
            }
        } catch (HttpClientErrorException.NotFound exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found", exception);
        } catch (RestClientResponseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Catalog service request failed", exception);
        } catch (ResourceAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catalog service unavailable", exception);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid catalog service response", exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProductIdentity(UUID id) {}
}
