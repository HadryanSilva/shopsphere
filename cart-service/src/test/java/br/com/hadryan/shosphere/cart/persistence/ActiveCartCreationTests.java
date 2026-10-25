package br.com.hadryan.shosphere.cart.persistence;

import br.com.hadryan.shosphere.cart.client.CatalogClient;
import br.com.hadryan.shosphere.cart.mapper.request.AddProductRequest;
import br.com.hadryan.shosphere.cart.mapper.request.UpdateCartRequest;
import br.com.hadryan.shosphere.cart.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.cart.model.Cart;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import br.com.hadryan.shosphere.cart.service.impl.AddCartItemTransaction;
import br.com.hadryan.shosphere.cart.service.impl.AddProductToCartUseCaseImpl;
import br.com.hadryan.shosphere.cart.service.impl.GetCartUseCaseImpl;
import br.com.hadryan.shosphere.cart.service.impl.UpdateCartUseCaseImpl;
import br.com.hadryan.shosphere.cart.service.impl.UpdateCartItemTransaction;
import br.com.hadryan.shosphere.cart.service.impl.RemoveCartItemUseCaseImpl;
import br.com.hadryan.shosphere.cart.service.RemoveCartItemUseCase;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class ActiveCartCreationTests {
    private DriverManagerDataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private String schema;
    private final UUID productId = UUID.randomUUID();
    private final CatalogClient catalog = mock(CatalogClient.class);

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "cart_creation_test_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(CatalogClient.class, () -> catalog);
        context.registerBean("entityManagerFactory", LocalContainerEntityManagerFactoryBean.class, () -> {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(Cart.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.default_schema", schema, "hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        });
        context.register(PersistenceConfig.class);
        context.refresh();
        when(catalog.getProductById(productId)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Feign não deve executar dentro da transação");
            return new ProductResponse(productId, "Product", BigDecimal.TEN, "ACTIVE");
        });
        when(catalog.getProducts(java.util.List.of(productId))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Batch deve executar sem transação");
            return java.util.List.of(new ProductResponse(productId, "Product", BigDecimal.TEN, "ACTIVE"));
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (context != null) {
            context.close();
        }
        if (schema != null) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void quantityLimitIsAcceptedAndOverflowPreservesPersistedQuantity() throws Exception {
        var customerId = UUID.randomUUID();
        var useCase = context.getBean(AddProductToCartUseCaseImpl.class);
        var added = useCase.execute(customerId, new AddProductRequest(null, productId, Integer.MAX_VALUE - 1));
        useCase.execute(customerId, new AddProductRequest(null, productId, 1));
        assertEquals(Integer.MAX_VALUE, quantity(added.cartId()));

        var overflow = assertThrows(ResponseStatusException.class, () ->
                useCase.execute(customerId, new AddProductRequest(null, productId, 1)));
        assertEquals(409, overflow.getStatusCode().value());
        assertEquals(Integer.MAX_VALUE, quantity(added.cartId()));

        var explicitOverflow = assertThrows(ResponseStatusException.class, () ->
                useCase.execute(customerId, new AddProductRequest(added.cartId(), productId, Integer.MAX_VALUE)));
        assertEquals(409, explicitOverflow.getStatusCode().value());
        assertEquals(Integer.MAX_VALUE, quantity(added.cartId()));
    }

    @Test
    void createsFirstCartReusesActiveCartAndKeepsCustomersAndHistorySeparate() throws Exception {
        var customerId = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var insert = connection.prepareStatement(
                "INSERT INTO " + schema + ".carts (id, customer_id, status) VALUES (?, ?, 'ABANDONED')")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, customerId);
            insert.executeUpdate();
        }
        var useCase = context.getBean(AddProductToCartUseCaseImpl.class);
        var first = useCase.execute(customerId, new AddProductRequest(null, productId, 2));
        var second = useCase.execute(customerId, new AddProductRequest(null, productId, 3));
        var other = useCase.execute(UUID.randomUUID(), new AddProductRequest(null, productId, 1));
        assertEquals(first.cartId(), second.cartId());
        assertNotEquals(first.cartId(), other.cartId());
        assertEquals(5, quantity(first.cartId()));
        assertEquals(1, quantity(other.cartId()));
        var explicit = useCase.execute(customerId, new AddProductRequest(first.cartId(), productId, 1));
        assertEquals(first.cartId(), explicit.cartId());
        assertEquals(6, quantity(first.cartId()));
    }

    @Test
    void simultaneousFirstAdditionsCreateOneCartAndPreserveEveryQuantity() throws Exception {
        var customerId = UUID.randomUUID();
        var useCase = context.getBean(AddProductToCartUseCaseImpl.class);
        var ready = new CountDownLatch(4);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 4).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return useCase.execute(customerId, new AddProductRequest(null, productId, 1));
            })).toList();
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            var ids = new HashSet<UUID>();
            for (var future : futures) {
                ids.add(future.get(20, TimeUnit.SECONDS).cartId());
            }
            assertEquals(1, ids.size());
            assertEquals(4, quantity(ids.iterator().next()));
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT count(*) FROM " + schema + ".carts WHERE status = 'ACTIVE'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void explicitCartOfAnotherCustomerIsRejectedWithoutCatalogCall() {
        var useCase = context.getBean(AddProductToCartUseCaseImpl.class);
        var owned = useCase.execute(UUID.randomUUID(), new AddProductRequest(null, productId, 2));
        clearInvocations(catalog);
        var exception = assertThrows(ResponseStatusException.class, () ->
                useCase.execute(UUID.randomUUID(), new AddProductRequest(owned.cartId(), productId, 1)));
        assertEquals(404, exception.getStatusCode().value());
        verifyNoInteractions(catalog);
    }

    private int quantity(UUID cartId) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT quantity FROM " + schema + ".cart_items WHERE cart_id = ? AND product_id = ?")) {
            statement.setObject(1, cartId);
            statement.setObject(2, productId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    @Test
    void readsOnlyOwnedActiveCartWithItemsOutsideJpaSessionDespiteHistory() throws Exception {
        var customerId = UUID.randomUUID();
        insertHistory(customerId);
        var addProduct = context.getBean(AddProductToCartUseCaseImpl.class);
        var active = addProduct.execute(customerId, new AddProductRequest(null, productId, 2));
        addProduct.execute(UUID.randomUUID(), new AddProductRequest(null, productId, 9));
        clearInvocations(catalog);

        var response = context.getBean(GetCartUseCaseImpl.class).execute(customerId);

        assertEquals(active.cartId(), response.id());
        assertEquals(CartStatus.ACTIVE, response.status());
        assertEquals(1, response.items().size());
        assertEquals(productId, response.items().getFirst().productId());
        assertEquals(2, response.items().getFirst().quantity());
        assertEquals(new BigDecimal("20"), response.estimatedTotal());
        verify(catalog).getProducts(java.util.List.of(productId));
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void historyAndOtherCustomersActiveCartDoNotReplaceMissingActiveCart() throws Exception {
        var customerId = UUID.randomUUID();
        insertHistory(customerId);
        context.getBean(AddProductToCartUseCaseImpl.class)
                .execute(UUID.randomUUID(), new AddProductRequest(null, productId, 1));
        clearInvocations(catalog);

        var exception = assertThrows(ResponseStatusException.class, () ->
                context.getBean(GetCartUseCaseImpl.class).execute(customerId));

        assertEquals("Cart not found", exception.getReason());
        assertEquals(404, exception.getStatusCode().value());
        verifyNoInteractions(catalog);
    }

    @ParameterizedTest
    @EnumSource(CartStatus.class)
    void persistsAndReloadsEveryCartStatusAcceptedByDatabase(CartStatus status) throws Exception {
        var customerId = UUID.randomUUID();
        var added = context.getBean(AddProductToCartUseCaseImpl.class)
                .execute(customerId, new AddProductRequest(null, productId, 1));
        var repository = context.getBean(CartRepository.class);
        var transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));

        transaction.executeWithoutResult(transactionStatus -> {
            var cart = repository.findById(added.cartId()).orElseThrow();
            cart.setStatus(status);
            repository.saveAndFlush(cart);
        });

        // Outra sessão verifica a leitura do valor realmente persistido.
        assertEquals(status, repository.findById(added.cartId()).orElseThrow().getStatus());
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT status FROM " + schema + ".carts WHERE id = ?")) {
            statement.setObject(1, added.cartId());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(status.name(), result.getString(1));
            }
        }
        assertEquals(1, quantity(added.cartId()));
    }

    private void insertHistory(UUID customerId) throws Exception {
        try (var connection = dataSource.getConnection();
             var insertCart = connection.prepareStatement("INSERT INTO " + schema
                     + ".carts (id, customer_id, status) VALUES (?, ?, ?)");
             var insertItem = connection.prepareStatement("INSERT INTO " + schema
                     + ".cart_items (id, cart_id, product_id, quantity) VALUES (?, ?, ?, 99)")) {
            for (var status : java.util.List.of("ABANDONED", "CHECKED_OUT")) {
                var historicalCartId = UUID.randomUUID();
                insertCart.setObject(1, historicalCartId);
                insertCart.setObject(2, customerId);
                insertCart.setString(3, status);
                insertCart.executeUpdate();
                insertItem.setObject(1, UUID.randomUUID());
                insertItem.setObject(2, historicalCartId);
                insertItem.setObject(3, UUID.randomUUID());
                insertItem.executeUpdate();
            }
        }
    }

    @Test
    void patchReplacesQuantityOnRetriesAndDeleteRemovesOnlyOwnedActiveItem() throws Exception {
        var customerId = UUID.randomUUID();
        insertHistory(customerId);
        var addProduct = context.getBean(AddProductToCartUseCaseImpl.class);
        var owned = addProduct.execute(customerId, new AddProductRequest(null, productId, 2));
        var other = addProduct.execute(UUID.randomUUID(), new AddProductRequest(null, productId, 9));
        var update = context.getBean(UpdateCartUseCaseImpl.class);
        update.execute(customerId, productId, new UpdateCartRequest(3));
        update.execute(customerId, productId, new UpdateCartRequest(3));
        assertEquals(3, quantity(owned.cartId()));
        assertEquals(9, quantity(other.cartId()));
        clearInvocations(catalog);

        var missing = assertThrows(ResponseStatusException.class, () ->
                update.execute(customerId, UUID.randomUUID(), new UpdateCartRequest(3)));
        assertEquals(404, missing.getStatusCode().value());
        verifyNoInteractions(catalog);

        var remove = context.getBean(RemoveCartItemUseCase.class);
        remove.execute(customerId, productId);
        remove.execute(customerId, productId);
        verifyNoInteractions(catalog);
        assertEquals(9, quantity(other.cartId()));
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT count(*) FROM " + schema + ".cart_items WHERE cart_id = ?")) {
            statement.setObject(1, owned.cartId());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
            try (var history = connection.prepareStatement("SELECT count(*) FROM " + schema
                    + ".cart_items i JOIN " + schema + ".carts c ON c.id = i.cart_id"
                    + " WHERE c.customer_id = ? AND c.status <> 'ACTIVE' AND i.quantity = 99")) {
                history.setObject(1, customerId);
                try (var result = history.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(2, result.getInt(1));
                }
            }
        }
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CartRepository.class)
    @Import({AddCartItemTransaction.class, AddProductToCartUseCaseImpl.class, GetCartUseCaseImpl.class,
            UpdateCartUseCaseImpl.class, UpdateCartItemTransaction.class, RemoveCartItemUseCaseImpl.class})
    static class PersistenceConfig {
        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
