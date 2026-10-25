package br.com.hadryan.shosphere.order.persistence;

import br.com.hadryan.shosphere.order.client.CartClient;
import br.com.hadryan.shosphere.order.client.CatalogClient;
import br.com.hadryan.shosphere.order.controller.OrderController;
import br.com.hadryan.shosphere.order.mapper.request.CreateOrderRequest;
import br.com.hadryan.shosphere.order.mapper.request.OrderItemRequest;
import br.com.hadryan.shosphere.order.mapper.request.ShippingAddressRequest;
import br.com.hadryan.shosphere.order.mapper.response.CartItemResponse;
import br.com.hadryan.shosphere.order.mapper.response.CartResponse;
import br.com.hadryan.shosphere.order.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.order.model.Order;
import br.com.hadryan.shosphere.order.model.ShippingAddress;
import br.com.hadryan.shosphere.order.model.enums.OrderStatus;
import br.com.hadryan.shosphere.order.repository.OrderRepository;
import br.com.hadryan.shosphere.order.service.GetOrderByIdUseCase;
import br.com.hadryan.shosphere.order.service.impl.GetOrderByIdUseCaseImpl;
import br.com.hadryan.shosphere.order.service.impl.CreateOrderTransaction;
import br.com.hadryan.shosphere.order.service.impl.CreateOrderUseCaseImpl;
import br.com.hadryan.shosphere.order.service.impl.OrderIdempotency;
import br.com.hadryan.shosphere.order.util.CurrentUserTokenProvider;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class OrderCreationPersistenceTests {
    private DriverManagerDataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private String schema;
    private ValidatorFactory validation;
    private final CartClient cart = mock(CartClient.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final CurrentUserTokenProvider token = mock(CurrentUserTokenProvider.class);
    private final UUID firstId = UUID.randomUUID();
    private final UUID secondId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "order_creation_test_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        validation = Validation.buildDefaultValidatorFactory();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(CartClient.class, () -> cart);
        context.registerBean(CatalogClient.class, () -> catalog);
        context.registerBean(CurrentUserTokenProvider.class, () -> token);
        context.registerBean(Validator.class, validation::getValidator);
        context.registerBean("entityManagerFactory", LocalContainerEntityManagerFactoryBean.class, () -> {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(Order.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.default_schema", schema, "hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        });
        context.register(PersistenceConfig.class);
        context.refresh();
        when(token.getCurrentUserToken()).thenReturn("Bearer test-token");
        when(cart.getCustomerCart("Bearer test-token")).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Cart deve ser consultado sem transação");
            return new CartResponse(UUID.randomUUID(), "ACTIVE",
                    List.of(new CartItemResponse(firstId, 3), new CartItemResponse(secondId, 2)));
        });
        when(catalog.getProducts(List.of(firstId, secondId))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Catalog deve ser consultado sem transação");
            return List.of(new ProductResponse(secondId, "Second product", new BigDecimal("1.20"), "EUR", "ACTIVE"),
                    new ProductResponse(firstId, "First product", new BigDecimal("0.10"), "EUR", "ACTIVE"));
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (context != null) context.close();
        if (validation != null) validation.close();
        if (schema != null) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void readsCommittedOrderWithItemsAndRestrictsLookupToOwner() {
        var customerId = UUID.randomUUID();
        var created = context.getBean(CreateOrderUseCaseImpl.class).execute(customerId, UUID.randomUUID(), validRequest());
        clearInvocations(cart, catalog);
        var read = context.getBean(GetOrderByIdUseCase.class);

        var result = read.execute(customerId, created.id());
        assertEquals(created.id(), result.id());
        assertEquals(created.status(), result.status());
        assertEquals(created.items().size(), result.items().size());
        assertEquals(new HashSet<>(created.items()), new HashSet<>(result.items()));
        assertEquals(created.totalAmount(), result.totalAmount());
        assertEquals(created.currency(), result.currency());
        assertNotNull(result.updatedAt());
        var denied = assertThrows(ResponseStatusException.class,
                () -> read.execute(UUID.randomUUID(), created.id()));
        assertEquals(404, denied.getStatusCode().value());
        var missing = assertThrows(ResponseStatusException.class,
                () -> read.execute(customerId, UUID.randomUUID()));
        assertEquals(404, missing.getStatusCode().value());
        verifyNoInteractions(cart, catalog);
    }

    @Test
    void returnsCreatedAfterCommitAndKeepsFinancialAndAddressSnapshots() throws Exception {
        var customerId = UUID.randomUUID();
        var key = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("test-token").header("alg", "RS256").subject(customerId.toString()).build();
        var address = new ShippingAddressRequest("Customer", "Street 1", "Apartment 2", "Lisbon", "1000-001", "PT");
        var request = new CreateOrderRequest(List.of(new OrderItemRequest(UUID.randomUUID(), 999)), address);

        var result = context.getBean(OrderController.class).createOrder(jwt, key, request);

        assertEquals(201, result.getStatusCode().value());
        var response = result.getBody();
        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals("/api/v1/orders/" + response.id(), result.getHeaders().getLocation().toString());
        assertEquals(OrderStatus.PENDING_STOCK, response.status());
        assertEquals(new BigDecimal("2.70"), response.subtotal());
        assertEquals(new BigDecimal("2.70"), response.totalAmount());
        assertEquals("EUR", response.currency());
        assertNull(response.paymentStatus());
        assertNotNull(response.updatedAt());
        assertEquals(new BigDecimal("0.30"), response.items().getFirst().lineTotal());
        assertEquals(new BigDecimal("2.40"), response.items().getLast().lineTotal());

        // Outra conexão observa o commit antes de qualquer nova operação da aplicação.
        assertRowCount("orders", 1);
        assertRowCount("order_items", 2);
        when(catalog.getProducts(anyList())).thenReturn(
                List.of(new ProductResponse(firstId, "Changed name", new BigDecimal("99.00"), "EUR", "INACTIVE")));
        try (var entityManager = context.getBean(EntityManagerFactory.class).createEntityManager()) {
            var saved = entityManager.find(Order.class, response.id());
            assertEquals(customerId, saved.getCustomerId());
            assertEquals(key.toString(), saved.getIdempotencyKey());
            assertEquals(new BigDecimal("2.70"), saved.getSubtotal());
            assertEquals(saved.getSubtotal(), saved.getTotalAmount());
            assertEquals(new BigDecimal("0.00"), saved.getShippingAmount());
            assertEquals(new BigDecimal("0.00"), saved.getDiscountAmount());
            assertEquals("Customer", saved.getShippingAddress().getRecipient());
            assertEquals("Street 1", saved.getShippingAddress().getAddressLine());
            assertEquals("Apartment 2", saved.getShippingAddress().getAddressLine2());
            assertEquals("Lisbon", saved.getShippingAddress().getCity());
            assertEquals("1000-001", saved.getShippingAddress().getPostalCode());
            assertEquals("PT", saved.getShippingAddress().getCountryCode());
            var first = saved.getItems().stream().filter(item -> item.getProductId().equals(firstId)).findFirst().orElseThrow();
            assertEquals("First product", first.getProductName());
            assertEquals(3, first.getQuantity());
            assertEquals(new BigDecimal("0.10"), first.getUnitPrice());
            assertEquals(new BigDecimal("0.30"), first.getLineTotal());
        }
        verify(cart).getCustomerCart("Bearer test-token");
        verify(catalog).getProducts(List.of(firstId, secondId));
    }

    @Test
    void itemConstraintFailureRollsBackHeaderAndEveryItem() throws Exception {
        var customerId = UUID.randomUUID();
        var key = UUID.randomUUID();
        var order = Order.create(customerId, key, "EUR",
                new ShippingAddress("Customer", "Street", "City", "1000", "PT"));
        order.addItem(firstId, "First", BigDecimal.TEN, 1);
        // Simula uma falha de integridade na gravação posterior ao cabeçalho.
        order.addItem(firstId, "Duplicate", BigDecimal.TEN, 1);

        assertThrows(DataIntegrityViolationException.class, () -> context.getBean(CreateOrderTransaction.class).execute(order));

        assertRowCount("orders", 0);
        assertRowCount("order_items", 0);
        verifyNoInteractions(cart, catalog, token);
        var retry = context.getBean(CreateOrderUseCaseImpl.class).execute(customerId, key, validRequest());
        assertNotNull(retry.id());
        assertRowCount("orders", 1);
        assertRowCount("order_items", 2);
    }

    @Test
    void retriesReturnStoredOrderWithoutDependingOnCurrentCartOrCatalog() throws Exception {
        var customerId = UUID.randomUUID();
        var key = UUID.randomUUID();
        var useCase = context.getBean(CreateOrderUseCaseImpl.class);
        var original = useCase.execute(customerId, key, validRequest());
        clearInvocations(cart, catalog, token);
        when(cart.getCustomerCart(anyString())).thenThrow(new IllegalStateException("Cart unavailable"));
        when(catalog.getProducts(anyList())).thenThrow(new IllegalStateException("Catalog unavailable"));

        var replay = useCase.execute(customerId, key, new CreateOrderRequest(
                List.of(new OrderItemRequest(UUID.randomUUID(), 999)), validRequest().shippingAddress()));

        assertEquals(original.id(), replay.id());
        assertEquals(original.status(), replay.status());
        assertEquals(original.totalAmount(), replay.totalAmount());
        assertEquals(new HashSet<>(original.items()), new HashSet<>(replay.items()));
        verifyNoInteractions(cart, catalog, token);
        assertRowCount("orders", 1);
        assertRowCount("order_items", 2);

        var failure = assertThrows(ResponseStatusException.class, () -> useCase.execute(customerId, key,
                new CreateOrderRequest(null, new ShippingAddressRequest("Other recipient", "Street 1", null,
                        "Lisbon", "1000-001", "PT"))));
        assertEquals(409, failure.getStatusCode().value());
        verifyNoInteractions(cart, catalog, token);
    }

    @Test
    void keyIsScopedByCustomerAndNewKeyCreatesNewOrder() throws Exception {
        var customerId = UUID.randomUUID();
        var key = UUID.randomUUID();
        var useCase = context.getBean(CreateOrderUseCaseImpl.class);
        var first = useCase.execute(customerId, key, validRequest());
        var other = useCase.execute(UUID.randomUUID(), key, validRequest());
        var newIntention = useCase.execute(customerId, UUID.randomUUID(), validRequest());

        assertNotEquals(first.id(), other.id());
        assertNotEquals(first.id(), newIntention.id());
        assertNotEquals(other.id(), newIntention.id());
        assertRowCount("orders", 3);
        assertRowCount("order_items", 6);
    }

    @Test
    void fourConcurrentRequestsWithSameKeyCommitExactlyOneOrder() throws Exception {
        var customerId = UUID.randomUUID();
        var key = UUID.randomUUID();
        var useCase = context.getBean(CreateOrderUseCaseImpl.class);
        synchronizeCatalogCalls(4);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 4).mapToObj(index -> executor.submit(() ->
                    useCase.execute(customerId, key, validRequest()))).toList();
            var ids = new HashSet<UUID>();
            for (var future : futures) {
                var response = future.get(30, TimeUnit.SECONDS);
                ids.add(response.id());
                assertEquals(new BigDecimal("2.70"), response.totalAmount());
                assertEquals(2, response.items().size());
            }
            assertEquals(1, ids.size());
        }
        assertRowCount("orders", 1);
        assertRowCount("order_items", 2);
        verify(cart, times(4)).getCustomerCart("Bearer test-token");
        verify(catalog, times(4)).getProducts(List.of(firstId, secondId));
    }

    @Test
    void concurrentDifferentAddressesCannotShareSameKey() throws Exception {
        var customerId = UUID.randomUUID();
        var key = UUID.randomUUID();
        var useCase = context.getBean(CreateOrderUseCaseImpl.class);
        synchronizeCatalogCalls(2);
        var requests = List.of(validRequest(), new CreateOrderRequest(null,
                new ShippingAddressRequest("Other recipient", "Street 1", null, "Lisbon", "1000-001", "PT")));
        int successes = 0;
        int conflicts = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = requests.stream().map(request -> executor.submit(() ->
                    useCase.execute(customerId, key, request))).toList();
            for (var future : futures) {
                try {
                    assertNotNull(future.get(30, TimeUnit.SECONDS).id());
                    successes++;
                } catch (ExecutionException exception) {
                    var failure = assertInstanceOf(ResponseStatusException.class, exception.getCause());
                    assertEquals(409, failure.getStatusCode().value());
                    conflicts++;
                }
            }
        }
        assertEquals(1, successes);
        assertEquals(1, conflicts);
        assertRowCount("orders", 1);
        assertRowCount("order_items", 2);
    }

    private CreateOrderRequest validRequest() {
        return new CreateOrderRequest(null,
                new ShippingAddressRequest("Customer", "Street 1", null, "Lisbon", "1000-001", "PT"));
    }

    private void synchronizeCatalogCalls(int count) {
        var barrier = new CyclicBarrier(count);
        when(catalog.getProducts(List.of(firstId, secondId))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            barrier.await(15, TimeUnit.SECONDS);
            return List.of(new ProductResponse(firstId, "First", new BigDecimal("0.10"), "EUR", "ACTIVE"),
                    new ProductResponse(secondId, "Second", new BigDecimal("1.20"), "EUR", "ACTIVE"));
        });
    }

    private void assertRowCount(String table, int count) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
            assertTrue(result.next());
            assertEquals(count, result.getInt(1));
        }
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @Import({CreateOrderUseCaseImpl.class, CreateOrderTransaction.class, OrderIdempotency.class, OrderController.class,
            GetOrderByIdUseCaseImpl.class})
    static class PersistenceConfig {
        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
