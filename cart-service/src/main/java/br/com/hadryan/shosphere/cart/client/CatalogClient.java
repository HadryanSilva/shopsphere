package br.com.hadryan.shosphere.cart.client;

import br.com.hadryan.shosphere.cart.mapper.response.ProductResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;
import java.util.List;

@FeignClient(
        name = "catalog-service",
        url = "${services.catalog.url}",
        path = "/api/v1/products"
)
public interface CatalogClient {

    @GetMapping("/{id}")
    ProductResponse getProductById(@PathVariable("id") UUID id);

    @GetMapping("/batch")
    List<ProductResponse> getProducts(@RequestParam("productIds") List<UUID> productIds);

}
