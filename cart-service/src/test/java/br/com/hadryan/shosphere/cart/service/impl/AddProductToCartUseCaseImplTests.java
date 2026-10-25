package br.com.hadryan.shosphere.cart.service.impl;

import br.com.hadryan.shosphere.cart.client.CatalogClient;
import br.com.hadryan.shosphere.cart.mapper.request.AddProductRequest;
import br.com.hadryan.shosphere.cart.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AddProductToCartUseCaseImplTests {
    private final CartRepository repository = mock(CartRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final AddCartItemTransaction transaction = mock(AddCartItemTransaction.class);
    private final AddProductToCartUseCaseImpl useCase = new AddProductToCartUseCaseImpl(repository, catalog, transaction);
    private final UUID customerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    void addsToOwnedActiveCartForExistingClients() {
        var request = new AddProductRequest(UUID.randomUUID(), productId, 2);
        when(repository.existsByIdAndCustomerIdAndStatus(request.cartId(), customerId, CartStatus.ACTIVE)).thenReturn(true);
        when(catalog.getProductById(productId)).thenReturn(product("ACTIVE"));
        when(transaction.execute(customerId, request)).thenReturn(request.cartId());
        var response = useCase.execute(customerId, request);
        verify(transaction).execute(customerId, request);
        assertEquals(request.cartId(), response.cartId());
        assertEquals(productId, response.productId());
        assertEquals(2, response.quantity());
    }

    @Test
    void supportsFirstAdditionWithoutCartId() {
        var request = new AddProductRequest(null, productId, 2);
        var generatedId = UUID.randomUUID();
        when(catalog.getProductById(productId)).thenReturn(product("ACTIVE"));
        when(transaction.execute(customerId, request)).thenReturn(generatedId);
        var response = useCase.execute(customerId, request);
        assertEquals(generatedId, response.cartId());
        verifyNoInteractions(repository);
        var order = inOrder(catalog, transaction);
        order.verify(catalog).getProductById(productId);
        order.verify(transaction).execute(customerId, request);
    }

    @Test
    void rejectsForeignMissingOrInactiveCartBeforeCallingCatalog() {
        var request = new AddProductRequest(UUID.randomUUID(), productId, 2);
        var exception = assertThrows(ResponseStatusException.class, () -> useCase.execute(customerId, request));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(repository).existsByIdAndCustomerIdAndStatus(request.cartId(), customerId, CartStatus.ACTIVE);
        verifyNoInteractions(catalog, transaction);
    }

    @Test
    void inactiveProductDoesNotCreateCart() {
        when(catalog.getProductById(productId)).thenReturn(product("INACTIVE"));
        assertThrows(RuntimeException.class, () -> useCase.execute(customerId, new AddProductRequest(null, productId, 2)));
        verifyNoInteractions(transaction);
    }

    @Test
    void catalogFailureDoesNotCreateCart() {
        when(catalog.getProductById(productId)).thenThrow(new RuntimeException("Catalog unavailable"));
        assertThrows(RuntimeException.class, () -> useCase.execute(customerId, new AddProductRequest(null, productId, 2)));
        verifyNoInteractions(transaction);
    }

    private ProductResponse product(String status) {
        return new ProductResponse(productId, "Product", BigDecimal.TEN, status);
    }
}
