package br.com.hadryan.shosphere.order.persistence;

import br.com.hadryan.shosphere.order.client.*;
import br.com.hadryan.shosphere.order.config.SecurityConfig;
import br.com.hadryan.shosphere.order.controller.OrderController;
import br.com.hadryan.shosphere.order.exception.ApiExceptionHandler;
import br.com.hadryan.shosphere.order.mapper.request.CreateOrderRequest;
import br.com.hadryan.shosphere.order.mapper.request.ShippingAddressRequest;
import br.com.hadryan.shosphere.order.mapper.response.CartCheckoutResponse;
import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.mapper.response.ProductResponse;
import br.com.hadryan.shosphere.order.model.Order;
import br.com.hadryan.shosphere.order.model.ShippingAddress;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.repository.OrderRepository;
import br.com.hadryan.shosphere.order.service.impl.*;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class SnapshotCheckoutPersistenceTests {
    private DriverManagerDataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private ValidatorFactory validation;
    private String schema;
    private final UUID customer = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final CartCheckoutClient cart = mock(CartCheckoutClient.class);
    private final CatalogClient catalog = mock(CatalogClient.class);

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "snapshot_checkout_test_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        validation = Validation.buildDefaultValidatorFactory();
        openContext();
        when(cart.prepare(any(), eq(customer))).thenAnswer(invocation -> {
            assertOutsideTransaction();
            return remote(invocation.getArgument(0), "PREPARED", null);
        });
        when(cart.complete(any(), any())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            // Prove the order was committed before attempting the remote conclusion.
            assertTrue(context.getBean(OrderRepository.class).findById(invocation.getArgument(1)).isPresent());
            return remote(invocation.getArgument(0), "COMPLETED", invocation.getArgument(1));
        });
        when(cart.abort(any(), eq(customer))).thenAnswer(invocation -> {
            assertOutsideTransaction();
            assertEquals(CheckoutIntentStatus.ABORT_PENDING, tx().get(invocation.getArgument(0)).status());
            return remote(invocation.getArgument(0), "ABORTED", null);
        });
        when(catalog.getProducts(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            return List.of(new ProductResponse(product, "Catalog snapshot", new BigDecimal("12.50"), "EUR", "ACTIVE"));
        });
    }

    private void openContext() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("snapshot-test", Map.of("checkout.snapshot.enabled", "true")));
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(CartCheckoutClient.class, () -> cart);
        context.registerBean(CatalogClient.class, () -> catalog);
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

    private void assertOutsideTransaction() { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); }
    private SnapshotCheckoutUseCase checkout() { return context.getBean(SnapshotCheckoutUseCase.class); }
    private CheckoutIntentTransaction tx() { return context.getBean(CheckoutIntentTransaction.class); }
    private CreateOrderRequest request() { return new CreateOrderRequest(null, new ShippingAddressRequest("Customer", "Street", null, "Lisbon", "1000-001", "PT")); }
    private CheckoutIntentSnapshot intent() { return tx().find(customer, key).orElseThrow(); }
    private OrderResponse execute() { return checkout().execute(customer, key, request()); }

    private CartCheckoutResponse remote(UUID id, String status, UUID orderId) {
        var time = Instant.parse("2026-10-27T12:00:00Z");
        return new CartCheckoutResponse(id, customer, cartId, status, List.of(new CartCheckoutResponse.Item(product, 2)),
                orderId, time, "COMPLETED".equals(status) ? time : null, "ABORTED".equals(status) ? time : null);
    }

    @Test
    void commitsFinancialSnapshotAndReplayDoesNotRepriceOrPrepareAgain() {
        var response = execute();
        assertEquals(new BigDecimal("25.00"), response.totalAmount());
        assertEquals(cartId, context.getBean(OrderRepository.class).findById(response.id()).orElseThrow().getSourceCartId());
        assertEquals(CheckoutIntentStatus.COMPLETED, intent().status());
        assertEquals(response, execute());
        verify(cart).prepare(any(), eq(customer));
        verify(cart).complete(any(), eq(response.id()));
        verify(catalog).getProducts(List.of(product));
    }

    @Test
    void httpContractKeepsIdentityLocation201ReplayAndOwnerIsolation() throws Exception {
        try (var web = new AnnotationConfigWebApplicationContext()) {
            web.setParent(context);
            web.setServletContext(new MockServletContext());
            web.register(MvcConfig.class, SecurityConfig.class, OrderController.class, ApiExceptionHandler.class);
            web.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
            var body = "{\"items\":[{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":99}],"
                    + "\"shippingAddress\":{\"recipient\":\"Customer\",\"addressLine\":\"Street\",\"city\":\"Lisbon\",\"postalCode\":\"1000-001\",\"countryCode\":\"PT\"}}";
            var result = mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + customer)
                            .header("Idempotency-Key", key).contentType("application/json").content(body))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(25))
                    .andExpect(jsonPath("$.items[0].quantity").value(2)).andExpect(jsonPath("$.status").value("PENDING_STOCK")).andReturn();
            var location = result.getResponse().getHeader("Location");
            assertNotNull(location);
            mvc.perform(get(location).header("Authorization", "Bearer " + customer)).andExpect(status().isOk());
            mvc.perform(get(location).header("Authorization", "Bearer " + UUID.randomUUID())).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + customer)
                            .header("Idempotency-Key", key).contentType("application/json").content(body))
                    .andExpect(status().isCreated()).andExpect(header().string("Location", location));
            mvc.perform(post("/api/v1/orders").header("Idempotency-Key", key).contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void cartTimeoutPreservesIntentAndRetryUsesTheSameCheckoutId() {
        when(cart.prepare(any(), eq(customer))).thenThrow(new CheckoutIntegrationFailure(503, CheckoutRetryCategory.UPSTREAM_UNAVAILABLE))
                .thenAnswer(invocation -> remote(invocation.getArgument(0), "PREPARED", null));
        assertStatus(503, this::execute);
        var pending = intent();
        assertEquals(CheckoutIntentStatus.PREPARING, pending.status());
        assertEquals(1, pending.attemptCount());
        execute();
        assertEquals(pending.checkoutId(), intent().checkoutId());
        verify(cart, times(2)).prepare(pending.checkoutId(), customer);
        assertEquals(1, count("orders"));
    }

    @Test
    void lostCompletionResponseStillReturns201AndRestartReplayFinishesPendingCart() {
        doThrow(new CheckoutIntegrationFailure(503, CheckoutRetryCategory.UPSTREAM_UNAVAILABLE))
                .doAnswer(invocation -> remote(invocation.getArgument(0), "COMPLETED", invocation.getArgument(1)))
                .when(cart).complete(any(), any());
        var response = execute();
        var pending = intent();
        assertEquals(CheckoutIntentStatus.COMPLETION_PENDING, pending.status());
        context.close();
        openContext();
        assertEquals(response, execute());
        assertEquals(CheckoutIntentStatus.COMPLETED, intent().status());
        verify(cart, times(2)).complete(pending.checkoutId(), response.id());
        verify(catalog).getProducts(anyList());
        assertEquals(1, count("orders"));
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409})
    void preparationBusinessFailureAbortsAndReplayKeepsTheOriginalFailure(int status) {
        when(cart.prepare(any(), eq(customer))).thenThrow(new ResponseStatusException(HttpStatus.valueOf(status)));
        assertStatus(status, this::execute);
        assertEquals(CheckoutIntentStatus.ABORTED, intent().status());
        assertStatus(status, this::execute);
        verify(cart).prepare(any(), eq(customer));
        verify(cart).abort(any(), eq(customer));
        verifyNoInteractions(catalog);
        assertEquals(0, count("orders"));
    }

    @Test
    void businessFailurePersistsAbortBeforeCallingCartAndFailedAbortIsRetried() {
        when(catalog.getProducts(anyList())).thenReturn(List.of());
        when(cart.abort(any(), eq(customer))).thenThrow(new CheckoutIntegrationFailure(502, CheckoutRetryCategory.UPSTREAM_REJECTED))
                .thenAnswer(invocation -> remote(invocation.getArgument(0), "ABORTED", null));
        assertStatus(409, this::execute);
        assertEquals(CheckoutIntentStatus.ABORT_PENDING, intent().status());
        assertStatus(409, this::execute);
        assertEquals(CheckoutIntentStatus.ABORTED, intent().status());
        verify(catalog).getProducts(anyList());
        assertEquals(0, count("orders"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "wrongCustomer", "wrongCheckout", "duplicate", "invalidQuantity", "missingCart", "completed"})
    void malformedPreparationRemainsRecoverableInsteadOfReleasingTheCart(String variant) {
        when(cart.prepare(any(), eq(customer))).thenAnswer(invocation -> {
            var response = remote(invocation.getArgument(0), "PREPARED", null);
            return switch (variant) {
                case "null" -> null;
                case "wrongCustomer" -> new CartCheckoutResponse(response.checkoutId(), UUID.randomUUID(), cartId, "PREPARED", response.items(), null, response.preparedAt(), null, null);
                case "wrongCheckout" -> remote(UUID.randomUUID(), "PREPARED", null);
                case "duplicate" -> new CartCheckoutResponse(response.checkoutId(), customer, cartId, "PREPARED", List.of(response.items().getFirst(), response.items().getFirst()), null, response.preparedAt(), null, null);
                case "invalidQuantity" -> new CartCheckoutResponse(response.checkoutId(), customer, cartId, "PREPARED", List.of(new CartCheckoutResponse.Item(product, 0)), null, response.preparedAt(), null, null);
                case "missingCart" -> new CartCheckoutResponse(response.checkoutId(), customer, null, "PREPARED", response.items(), null, response.preparedAt(), null, null);
                default -> remote(response.checkoutId(), "COMPLETED", UUID.randomUUID());
            };
        });
        assertStatus(502, this::execute);
        assertEquals(CheckoutIntentStatus.PREPARING, intent().status());
        assertEquals(0, count("orders"));
        verify(cart, never()).abort(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "duplicate", "badCurrency", "missingName"})
    void malformedCatalogResponseKeepsPreparedSnapshotForRetry(String variant) {
        var valid = new ProductResponse(product, "Product", BigDecimal.TEN, "EUR", "ACTIVE");
        when(catalog.getProducts(anyList())).thenReturn(switch (variant) {
            case "null" -> null;
            case "duplicate" -> List.of(valid, valid);
            case "badCurrency" -> List.of(new ProductResponse(product, "Product", BigDecimal.TEN, "invalid", "ACTIVE"));
            default -> List.of(new ProductResponse(product, null, BigDecimal.TEN, "EUR", "ACTIVE"));
        });
        assertStatus(502, this::execute);
        assertEquals(CheckoutIntentStatus.PREPARED, intent().status());
        verify(cart, never()).abort(any(), any());
    }

    @Test
    void unsupportedMonetaryAmountAbortsWithoutPersistingOrder() {
        when(catalog.getProducts(anyList())).thenReturn(List.of(new ProductResponse(product, "Product", new BigDecimal("1.001"), "EUR", "ACTIVE")));
        assertStatus(409, this::execute);
        assertEquals(CheckoutFailureReason.MONETARY_CONFLICT, intent().failureReason());
        assertEquals(CheckoutIntentStatus.ABORTED, intent().status());
        assertEquals(0, count("orders"));
    }

    @Test
    void concurrentSameKeyCreatesOneOrderAndBothRequestsConverge() throws Exception {
        var barrier = new CyclicBarrier(2);
        when(catalog.getProducts(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            barrier.await(10, TimeUnit.SECONDS);
            return List.of(new ProductResponse(product, "Product", BigDecimal.TEN, "EUR", "ACTIVE"));
        });
        var results = race(this::execute, this::execute);
        assertEquals(((OrderResponse) results.getFirst()).id(), ((OrderResponse) results.getLast()).id());
        assertEquals(1, count("orders"));
        assertEquals(1, count("checkout_intents"));
    }

    @Test
    void differentKeysCannotPersistTwoOrdersForTheSameCartEvenIfUpstreamAllowsBoth() throws Exception {
        var otherKey = UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        when(catalog.getProducts(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            barrier.await(10, TimeUnit.SECONDS);
            return List.of(new ProductResponse(product, "Product", BigDecimal.TEN, "EUR", "ACTIVE"));
        });
        var results = race(() -> statusOrSuccess(this::execute),
                () -> statusOrSuccess(() -> checkout().execute(customer, otherKey, request())));
        assertTrue(results.contains(200));
        assertTrue(results.contains(409));
        assertEquals(1, count("orders"));
    }

    @Test
    void catalogTimeoutKeepsSnapshotAndResumeDoesNotReadCartAgain() {
        when(catalog.getProducts(anyList())).thenThrow(mock(feign.RetryableException.class))
                .thenReturn(List.of(new ProductResponse(product, "Product", BigDecimal.TEN, "EUR", "ACTIVE")));
        assertStatus(503, this::execute);
        var prepared = intent();
        assertEquals(CheckoutIntentStatus.PREPARED, prepared.status());
        assertNotNull(checkout().resume(prepared.checkoutId()).id());
        verify(cart).prepare(prepared.checkoutId(), customer);
        assertEquals(CheckoutIntentStatus.COMPLETED, intent().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrongOrder", "wrongCart", "wrongCheckout"})
    void malformedConclusionKeepsOrderAndCompletionPending(String variant) {
        doAnswer(invocation -> {
            var valid = remote(invocation.getArgument(0), "COMPLETED", invocation.getArgument(1));
            return new CartCheckoutResponse(variant.equals("wrongCheckout") ? UUID.randomUUID() : valid.checkoutId(),
                    customer, variant.equals("wrongCart") ? UUID.randomUUID() : cartId, "COMPLETED", valid.items(),
                    variant.equals("wrongOrder") ? UUID.randomUUID() : valid.orderId(), valid.preparedAt(), valid.completedAt(), null);
        }).when(cart).complete(any(), any());
        assertNotNull(execute().id());
        assertEquals(CheckoutIntentStatus.COMPLETION_PENDING, intent().status());
        assertEquals(1, count("orders"));
        verify(cart, never()).abort(any(), any());
    }

    @Test
    void databaseFailureAfterRemotePreparationCanResumeSameIntent() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + schema + ".checkout_intents ADD CONSTRAINT test_reject_preparation CHECK (status <> 'PREPARED')");
        }
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, this::execute);
        var original = intent();
        assertEquals(CheckoutIntentStatus.PREPARING, original.status());
        assertEquals(0, count("orders"));
        verify(cart, never()).abort(any(), any());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + schema + ".checkout_intents DROP CONSTRAINT test_reject_preparation");
        }
        assertNotNull(execute().id());
        verify(cart, times(2)).prepare(original.checkoutId(), customer);
    }

    @Test
    void legacyReplayDoesNotPrepareCartOrInventOrigin() {
        var saved = Order.create(customer, key, "EUR", new ShippingAddress("Customer", "Street", null, "Lisbon", "1000-001", "PT"));
        saved.addItem(product, "Legacy", BigDecimal.TEN, 1);
        var response = context.getBean(CreateOrderTransaction.class).execute(saved);
        assertEquals(response.id(), execute().id());
        assertEquals(0, count("checkout_intents"));
        verifyNoInteractions(cart, catalog);
    }

    @Test
    void addressMismatchReturns409WithoutAnotherUpstreamRequest() {
        execute();
        clearInvocations(cart, catalog);
        assertStatus(409, () -> checkout().execute(customer, key, new CreateOrderRequest(null,
                new ShippingAddressRequest("Other", "Street", null, "Lisbon", "1000-001", "PT"))));
        verifyNoInteractions(cart, catalog);
    }

    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
                return (Object) action.call();
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.getFirst().get(20, TimeUnit.SECONDS), futures.getLast().get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); }
    }
    private int statusOrSuccess(Callable<?> action) throws Exception {
        try { action.call(); return 200; } catch (ResponseStatusException failure) { return failure.getStatusCode().value(); }
    }
    private void assertStatus(int status, Runnable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
    private int count(String table) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
            assertTrue(result.next()); return result.getInt(1);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private CheckoutRecoveryJob recovery(int batch) {
        return new CheckoutRecoveryJob(tx(), checkout(), batch, java.time.Duration.ofSeconds(60), java.time.Duration.ofMinutes(15));
    }

    @Test
    void lockedIntentDoesNotBlockSelectionOrClaimOfOtherWork() throws Exception {
        var first = tx().start(customer, key, new ShippingAddress("Customer", "Street", null, "Lisbon", "1000-001", "PT"));
        var second = tx().start(UUID.randomUUID(), UUID.randomUUID(), new ShippingAddress("Other", "Street", null, "Lisbon", "1000-001", "PT"));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT checkout_id FROM " + schema + ".checkout_intents WHERE checkout_id = ? FOR UPDATE")) {
                statement.setObject(1, first.checkoutId());
                try (var result = statement.executeQuery()) { assertTrue(result.next()); }
                try (var executor = Executors.newSingleThreadExecutor()) {
                    executor.submit(() -> {
                        assertEquals(List.of(second.checkoutId()), tx().due(Instant.now(), 1));
                        assertTrue(tx().claim(first.checkoutId(), Instant.now(), java.time.Duration.ofSeconds(60)).isEmpty());
                        assertTrue(tx().claim(second.checkoutId(), Instant.now(), java.time.Duration.ofSeconds(60)).isPresent());
                    }).get(5, TimeUnit.SECONDS);
                }
            } finally { connection.rollback(); }
        }
    }

    @Test
    void workerPersistsBackoffAfterDatabaseFailureAndLaterResumesSameRemotePreparation() throws Exception {
        var pending = tx().start(customer, key, new ShippingAddress("Customer", "Street", null, "Lisbon", "1000-001", "PT"));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + schema + ".checkout_intents ADD CONSTRAINT test_recovery_failure CHECK (status <> 'PREPARED')");
        }
        recovery(50).runOnce();
        assertEquals(CheckoutIntentStatus.PREPARING, intent().status());
        assertEquals(1, intent().attemptCount());
        assertEquals(CheckoutRetryCategory.PERSISTENCE_FAILURE, intent().lastFailureCategory());
        assertTrue(tx().due(Instant.now(), 50).isEmpty());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + schema + ".checkout_intents DROP CONSTRAINT test_recovery_failure");
        }
        makeDue(pending.checkoutId());
        recovery(50).runOnce();
        assertEquals(CheckoutIntentStatus.COMPLETED, intent().status());
        assertEquals(1, count("orders"));
        verify(cart, times(2)).prepare(pending.checkoutId(), customer);
    }

    private void makeDue(UUID id) {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("UPDATE " + schema
                + ".checkout_intents SET next_attempt_at = now() - interval '1 second' WHERE checkout_id = ?", id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepare", "catalog", "complete", "abort"})
    void workerRecoversEachDurableBoundaryAfterRestartWithoutUserJwt(String boundary) {
        var failure = new CheckoutIntegrationFailure(503, CheckoutRetryCategory.UPSTREAM_UNAVAILABLE);
        switch (boundary) {
            case "prepare" -> doThrow(failure).when(cart).prepare(any(), any());
            case "catalog" -> doThrow(new feign.RetryableException(503, "unavailable", feign.Request.HttpMethod.GET,
                    (Long) null, feign.Request.create(feign.Request.HttpMethod.GET, "http://catalog", Map.of(), null,
                            java.nio.charset.StandardCharsets.UTF_8, null))).when(catalog).getProducts(anyList());
            case "complete" -> doThrow(failure).when(cart).complete(any(), any());
            case "abort" -> {
                when(catalog.getProducts(anyList())).thenReturn(List.of());
                doThrow(failure).when(cart).abort(any(), any());
            }
        }
        try { execute(); } catch (ResponseStatusException expected) { }
        var id = intent().checkoutId();
        context.close();
        openContext();
        doAnswer(call -> remote(call.getArgument(0), "PREPARED", null)).when(cart).prepare(any(), any());
        doAnswer(call -> remote(call.getArgument(0), "COMPLETED", call.getArgument(1))).when(cart).complete(any(), any());
        doAnswer(call -> remote(call.getArgument(0), "ABORTED", null)).when(cart).abort(any(), any());
        doReturn(List.of(new ProductResponse(product, "Recovered", new BigDecimal("12.50"), "EUR", "ACTIVE")))
                .when(catalog).getProducts(anyList());
        makeDue(id);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        recovery(50).runOnce();
        assertEquals("abort".equals(boundary) ? CheckoutIntentStatus.ABORTED : CheckoutIntentStatus.COMPLETED, intent().status());
        assertEquals("abort".equals(boundary) ? 0 : 1, count("orders"));
        assertNull(intent().nextAttemptAt());
        assertTrue(tx().due(Instant.now().plusSeconds(10000), 50).isEmpty());
    }

    @Test
    void concurrentWorkersClaimOnlyOnceAndCrashLeaseSurvivesRestart() throws Exception {
        var pending = tx().start(customer, key, new ShippingAddress("Customer", "Street", null, "Lisbon", "1000-001", "PT"));
        var now = Instant.now();
        var results = race(() -> tx().claim(pending.checkoutId(), now, java.time.Duration.ofSeconds(60)),
                () -> tx().claim(pending.checkoutId(), now, java.time.Duration.ofSeconds(60)));
        assertEquals(1, results.stream().map(result -> (java.util.Optional<?>) result).filter(java.util.Optional::isPresent).count());
        context.close();
        openContext();
        assertTrue(tx().due(now.plusSeconds(30), 50).isEmpty());
        assertEquals(List.of(pending.checkoutId()), tx().due(now.plusSeconds(61), 50));
        makeDue(pending.checkoutId());
        recovery(50).runOnce();
        assertEquals(CheckoutIntentStatus.COMPLETED, intent().status());
    }

    @Test
    void backoffDoublesCapsAndIgnoresObsoleteOrAlreadyRecordedFailures() {
        var pending = tx().start(customer, key, new ShippingAddress("Customer", "Street", null, "Lisbon", "1000-001", "PT"));
        for (int seconds : new int[]{60, 120, 240, 480, 900, 900}) {
            var before = Instant.now();
            var failed = tx().retry(pending.checkoutId(), CheckoutIntentStatus.PREPARING, CheckoutRetryCategory.UPSTREAM_UNAVAILABLE);
            assertTrue(failed.nextAttemptAt().isAfter(before.plusSeconds(seconds - 1)));
            assertTrue(failed.nextAttemptAt().isBefore(Instant.now().plusSeconds(seconds + 1)));
        }
        assertEquals(6, tx().recoveryFailed(pending.checkoutId(), 5).attemptCount());
        assertEquals(6, tx().retry(pending.checkoutId(), CheckoutIntentStatus.PREPARED, CheckoutRetryCategory.PERSISTENCE_FAILURE).attemptCount());
        var next = tx().recoveryFailed(pending.checkoutId(), 6);
        assertEquals(7, next.attemptCount());
        assertEquals(CheckoutRetryCategory.PERSISTENCE_FAILURE, next.lastFailureCategory());
    }

    @Test
    void failingFirstIntentDoesNotStarveLaterBatchesAndFutureRetriesAreSkipped() {
        var first = tx().start(customer, key, new ShippingAddress("Customer", "Street", null, "Lisbon", "1000-001", "PT"));
        var second = tx().start(UUID.randomUUID(), UUID.randomUUID(), new ShippingAddress("Other", "Street", null, "Lisbon", "1000-001", "PT"));
        doThrow(new CheckoutIntegrationFailure(503, CheckoutRetryCategory.UPSTREAM_UNAVAILABLE)).when(cart).prepare(any(), any());
        assertEquals(1, tx().due(Instant.now(), 1).size());
        recovery(1).runOnce();
        recovery(1).runOnce();
        assertEquals(1, tx().get(first.checkoutId()).attemptCount());
        assertEquals(1, tx().get(second.checkoutId()).attemptCount());
        assertTrue(tx().due(Instant.now(), 50).isEmpty());
        verify(cart, times(2)).prepare(any(), any());
        assertEquals(0, count("orders"));
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @Import({SnapshotCheckoutUseCase.class, CheckoutIntentService.class, CheckoutIntentTransaction.class,
            OrderIdempotency.class, CreateOrderTransaction.class, GetOrderByIdUseCaseImpl.class})
    static class PersistenceConfig {
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }
    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    static class MvcConfig {
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256").subject(token).build();
        }
    }
}
