package br.com.hadryan.shopsphere.catalog.controller;

import br.com.hadryan.shopsphere.catalog.config.SecurityConfig;
import br.com.hadryan.shopsphere.catalog.exception.ApiExceptionHandler;
import br.com.hadryan.shopsphere.catalog.config.JwtSecurityConfig;
import br.com.hadryan.shopsphere.catalog.mapper.request.UpdateProductRequest;
import br.com.hadryan.shopsphere.catalog.model.Product;
import br.com.hadryan.shopsphere.catalog.model.Category;
import br.com.hadryan.shopsphere.catalog.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ProductController.class, CategoryController.class})
@ContextConfiguration(classes = {ProductController.class, CategoryController.class, SecurityConfig.class, JwtSecurityConfig.class, ApiExceptionHandler.class})
class CatalogRequestValidationTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private CreateProductUseCase createProduct;
    @MockitoBean private ListProductsUseCase listProducts;
    @MockitoBean private GetProductByIdUseCase getProduct;
    @MockitoBean private UpdateProductUseCase updateProduct;
    @MockitoBean private SearchProductsUseCase searchProducts;
    @MockitoBean private CreateCategoryUseCase createCategory;
    @MockitoBean private GetCategoryByIdUseCase getCategory;
    @MockitoBean private JwtDecoder decoder;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @MethodSource("invalidProducts")
    void creationRejectsInvalidProductBeforeUseCase(String field, Object value) throws Exception {
        var body = product();
        body.put(field, value);
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content(JSON.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(createProduct);
    }

    static Stream<Arguments> invalidProducts() {
        return Stream.of(Arguments.of("name", null), Arguments.of("name", " "),
                Arguments.of("name", "n".repeat(151)), Arguments.of("description", "d".repeat(2001)),
                Arguments.of("categoryId", null), Arguments.of("price", null),
                Arguments.of("price", BigDecimal.ZERO), Arguments.of("price", new BigDecimal("-1")),
                Arguments.of("price", new BigDecimal("0.001")),
                Arguments.of("price", new BigDecimal("100000000000000000.00")),
                Arguments.of("currency", null), Arguments.of("currency", "INVALID"),
                Arguments.of("imageUrl", "u".repeat(2049)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "GBP", "CHF"})
    void creationRejectsUnsupportedCurrenciesBeforeUseCase(String currency) throws Exception {
        var body = product();
        body.put("currency", currency);
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content(JSON.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(createProduct);
    }

    @ParameterizedTest
    @MethodSource("invalidUpdates")
    void patchRejectsInvalidProvidedFieldsBeforeUseCase(String field, Object value) throws Exception {
        mvc.perform(patch("/api/v1/products/{id}", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content(JSON.writeValueAsString(Map.of(field, value))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(updateProduct);
    }

    static Stream<Arguments> invalidUpdates() {
        return Stream.of(Arguments.of("name", ""), Arguments.of("name", " \n"), Arguments.of("name", "\u2003"),
                Arguments.of("name", "n".repeat(151)), Arguments.of("description", "d".repeat(2001)),
                Arguments.of("price", BigDecimal.ZERO), Arguments.of("price", BigDecimal.ONE.negate()),
                Arguments.of("price", new BigDecimal("0.001")),
                Arguments.of("price", new BigDecimal("100000000000000000.00")),
                Arguments.of("imageUrl", "u".repeat(2049)), Arguments.of("status", "INVALID"));
    }

    @ParameterizedTest
    @MethodSource("invalidCategories")
    void rejectsInvalidCategoryBeforeUseCase(String field, Object value) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", "Category");
        body.put("description", "Description");
        body.put(field, value);
        mvc.perform(post("/api/v1/categories").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content(JSON.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(createCategory);
    }

    static Stream<Arguments> invalidCategories() {
        return Stream.of(Arguments.of("name", null), Arguments.of("name", " "), Arguments.of("name", "n".repeat(101)),
                Arguments.of("description", null), Arguments.of("description", " "),
                Arguments.of("description", "d".repeat(501)));
    }

    @Test
    void acceptsProductAtColumnLimits() throws Exception {
        var body = product();
        body.put("name", "n".repeat(150));
        body.put("description", "d".repeat(2000));
        body.put("imageUrl", "https://example.test/" + "a".repeat(2027));
        body.put("price", new BigDecimal("99999999999999999.99"));
        when(createProduct.execute(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("EUR"));

        var captured = ArgumentCaptor.forClass(Product.class);
        verify(createProduct).execute(captured.capture());
        assertEquals(new BigDecimal("99999999999999999.99"), captured.getValue().getPrice());
        assertEquals(150, captured.getValue().getName().length());
        assertEquals(2048, captured.getValue().getImageUrl().length());
    }

    @Test
    void optionalProductFieldsRemainOptional() throws Exception {
        var body = product();
        body.remove("description");
        body.remove("imageUrl");
        when(createProduct.execute(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        mvc.perform(post("/api/v1/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void omittedPatchFieldsRemainOptional() throws Exception {
        var id = UUID.randomUUID();
        mvc.perform(patch("/api/v1/products/{id}", id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content("{\"price\":12.34}"))
                .andExpect(status().isNoContent());
        verify(updateProduct).execute(id, new UpdateProductRequest(null, null, new BigDecimal("12.34"), null, null));
    }

    @Test
    void emptyPatchKeepsExistingNoOpBehavior() throws Exception {
        var id = UUID.randomUUID();
        mvc.perform(patch("/api/v1/products/{id}", id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNoContent());
        verify(updateProduct).execute(id, new UpdateProductRequest(null, null, null, null, null));
    }

    @Test
    void acceptsCategoryAtColumnLimits() throws Exception {
        when(createCategory.execute(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));
        mvc.perform(post("/api/v1/categories").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content(JSON.writeValueAsString(
                                Map.of("name", "n".repeat(100), "description", "d".repeat(500)))))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> product() {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", "Product");
        body.put("description", "Description");
        body.put("categoryId", UUID.randomUUID().toString());
        body.put("price", new BigDecimal("10.00"));
        body.put("currency", "EUR");
        body.put("imageUrl", "https://example.test/product.png");
        return body;
    }
}
