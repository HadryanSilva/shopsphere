package br.com.hadryan.shosphere.order.client;

import br.com.hadryan.shosphere.order.mapper.response.ProductResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@FeignClient(
        name = "catalog-service",
        url = "${services.catalog.url}",
        path = "/api/v1/products"
)
public interface CatalogClient {

    @GetMapping("/batch")
    List<ProductResponse> getProducts(@RequestParam("productIds") List<UUID> productIds);

}
