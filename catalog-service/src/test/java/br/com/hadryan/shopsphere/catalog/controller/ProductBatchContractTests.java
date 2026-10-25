package br.com.hadryan.shopsphere.catalog.controller;

import br.com.hadryan.shopsphere.catalog.mapper.request.SearchProductsRequest;
import br.com.hadryan.shopsphere.catalog.exception.ApiExceptionHandler;
import br.com.hadryan.shopsphere.catalog.mapper.response.ProductResponse;
import br.com.hadryan.shopsphere.catalog.model.enums.Currency;
import br.com.hadryan.shopsphere.catalog.model.enums.ProductStatus;
import br.com.hadryan.shopsphere.catalog.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProductBatchContractTests {
    private final SearchProductsUseCase search = mock(SearchProductsUseCase.class);
    private final UUID firstId = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private final UUID secondId = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private final SearchProductsRequest request = new SearchProductsRequest(List.of(firstId, secondId));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProductController(mock(CreateProductUseCase.class),
                mock(ListProductsUseCase.class), mock(GetProductByIdUseCase.class),
                mock(UpdateProductUseCase.class), search)).setControllerAdvice(new ApiExceptionHandler()).build();
        when(search.execute(request)).thenReturn(List.of(
                product(firstId, "Keyboard", "USB keyboard", "49.90", ProductStatus.ACTIVE),
                product(secondId, "Mouse", "USB mouse", "19.90", ProductStatus.OUT_OF_STOCK)));
    }

    @Test
    void acceptsRepeatedQueryParametersAndReturnsSharedResponseContract() throws Exception {
        mvc.perform(get("/api/v1/products/batch").param("productIds", firstId.toString(), secondId.toString()))
                .andExpect(status().isOk())
                .andExpect(content().json(Files.readString(Path.of("..", "contracts", "catalog-products-batch-response.json"))));
        verify(search).execute(request);
    }

    @Test
    void acceptsCommaSeparatedIdsWithoutRequestBody() throws Exception {
        mvc.perform(get("/api/v1/products/batch").param("productIds", firstId + "," + secondId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        verify(search).execute(request);
    }

    @Test
    void returnsEmptyArrayWhenNoProductsAreFound() throws Exception {
        when(search.execute(request)).thenReturn(List.of());
        mvc.perform(get("/api/v1/products/batch").param("productIds", firstId.toString(), secondId.toString()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void rejectsMissingIds() throws Exception {
        mvc.perform(get("/api/v1/products/batch")).andExpect(status().isBadRequest());
        verifyNoInteractions(search);
    }

    @Test
    void rejectsInvalidUuid() throws Exception {
        mvc.perform(get("/api/v1/products/batch").param("productIds", "invalid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(search);
    }

    @Test
    void rejectsEmptyIds() throws Exception {
        mvc.perform(get("/api/v1/products/batch").param("productIds", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productIds"));
        verifyNoInteractions(search);
    }

    @Test
    void rejectsNullIdInsideList() throws Exception {
        mvc.perform(get("/api/v1/products/batch").param("productIds", firstId.toString(), ""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(search);
    }

    private ProductResponse product(UUID id, String name, String description, String price, ProductStatus status) {
        var timestamp = Instant.parse("2026-10-24T12:00:00Z");
        return new ProductResponse(id, name, description,
                UUID.fromString("10000000-0000-0000-0000-000000000001"), new BigDecimal(price),
                Currency.EUR, null, status, timestamp, timestamp);
    }
}
