package br.com.hadryan.shosphere.order.persistence;

import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.model.*;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.repository.CheckoutIntentRepository;
import br.com.hadryan.shosphere.order.repository.OrderRepository;
import br.com.hadryan.shosphere.order.service.impl.*;
import jakarta.persistence.EntityManagerFactory;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class CheckoutIntentPersistenceTests {
    private DriverManagerDataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private String schema;
    private final UUID customerId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID legacyId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "checkout_intent_test_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("3")
                .locations("classpath:db/migration").load().migrate();
        try (var connection = dataSource.getConnection(); var insert = connection.prepareStatement(
                "INSERT INTO " + schema + ".orders(id, customer_id, total_amount, subtotal) VALUES (?, ?, 25, 25)")) {
            insert.setObject(1, legacyId);
            insert.setObject(2, customerId);
            insert.executeUpdate();
        }
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        openContext();
    }

    private void openContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
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
        if (schema != null) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private CheckoutIntentService service() { return context.getBean(CheckoutIntentService.class); }
    private CheckoutIntentTransaction tx() { return context.getBean(CheckoutIntentTransaction.class); }
    private ShippingAddress address() { return new ShippingAddress("Customer", "Street", null, "Lisbon", "1000-001", "PT"); }
    private CheckoutIntentSnapshot start() { return service().start(customerId, key, address()); }
    private CheckoutIntentSnapshot prepared() {
        return tx().prepared(start().checkoutId(), cartId, List.of(new CheckoutIntentItem(productId, 2)));
    }
    private Order order(UUID orderKey) {
        var order = Order.create(customerId, orderKey, "EUR", address());
        order.addItem(productId, "Product snapshot", new BigDecimal("12.50"), 2);
        return order;
    }

    @Test
    void upgradePreservesLegacyOriginAndCurrentPublicCreation() {
        var repository = context.getBean(OrderRepository.class);
        var legacy = repository.findById(legacyId).orElseThrow();
        assertNull(legacy.getSourceCartId());
        assertNull(legacy.getShippingAddress());
        assertEquals(new BigDecimal("25.00"), legacy.getTotalAmount());
        assertThrows(IllegalStateException.class, () -> legacy.assignSourceCart(cartId));
        var created = context.getBean(CreateOrderTransaction.class).execute(order(key));
        assertNull(repository.findById(created.id()).orElseThrow().getSourceCartId());
        assertStatus(409, this::start);
        assertEquals(0, count("checkout_intents"));
    }

    @Test
    void restartPreservesAddressSnapshotAndRetryMetadata() {
        var prepared = prepared();
        var next = Instant.now().plusSeconds(60);
        var scheduled = tx().recordFailure(prepared.checkoutId(), CheckoutIntentStatus.PREPARED,
                CheckoutRetryCategory.UPSTREAM_UNAVAILABLE, next);
        context.close();
        openContext();
        var loaded = tx().get(prepared.checkoutId());
        assertEquals(prepared.items(), loaded.items());
        assertEquals(cartId, loaded.cartId());
        assertEquals("Street", loaded.shippingAddress().getAddressLine());
        assertNull(loaded.shippingAddress().getAddressLine2());
        assertEquals(scheduled.nextAttemptAt(), loaded.nextAttemptAt());
        assertEquals(1, loaded.attemptCount());
        assertEquals(CheckoutRetryCategory.UPSTREAM_UNAVAILABLE, loaded.lastFailureCategory());
        assertEquals(prepared.createdAt(), loaded.createdAt());
    }

    @Test
    void addressMismatchCannotReuseKeyAndAnotherCustomerCanUseSameKey() {
        var first = start();
        var changed = new ShippingAddress("Customer", "Street", "", "Lisbon", "1000-001", "PT");
        assertStatus(409, () -> service().start(customerId, key, changed));
        assertEquals(first.checkoutId(), start().checkoutId());
        assertNotEquals(first.checkoutId(), service().start(UUID.randomUUID(), key, address()).checkoutId());
        assertEquals(2, count("checkout_intents"));
    }

    @Test
    void preparedReplayKeepsOriginalSnapshotAndRejectsReplacement() {
        var prepared = prepared();
        assertEquals(prepared.updatedAt(), tx().prepared(prepared.checkoutId(), cartId,
                List.of(new CheckoutIntentItem(productId, 2))).updatedAt());
        assertStatus(409, () -> tx().prepared(prepared.checkoutId(), cartId, List.of(new CheckoutIntentItem(productId, 3))));
        assertStatus(409, () -> tx().prepared(prepared.checkoutId(), UUID.randomUUID(), List.of(new CheckoutIntentItem(productId, 2))));
        assertEquals(prepared.items(), tx().get(prepared.checkoutId()).items());
    }

    @Test
    void orderAndCompletionPendingCommitTogetherAndReplayKeepsFinancialSnapshot() {
        var intent = prepared();
        var response = tx().persistOrder(intent.checkoutId(), order(key));
        context.close();
        openContext();
        assertEquals(CheckoutIntentStatus.COMPLETION_PENDING, tx().get(intent.checkoutId()).status());
        assertEquals(response.id(), tx().get(intent.checkoutId()).orderId());
        assertEquals(cartId, context.getBean(OrderRepository.class).findById(response.id()).orElseThrow().getSourceCartId());
        var repriced = Order.create(customerId, key, "EUR", address());
        repriced.addItem(productId, "Changed current name", BigDecimal.ONE, 2);
        assertEquals(response, tx().persistOrder(intent.checkoutId(), repriced));
        var complete = tx().completed(intent.checkoutId());
        assertEquals(complete.updatedAt(), tx().completed(intent.checkoutId()).updatedAt());
        assertNull(complete.nextAttemptAt());
        assertStatus(409, () -> tx().requestAbort(intent.checkoutId(), CheckoutFailureReason.PRODUCT_CONFLICT));
        assertEquals(2, count("orders"));
        assertEquals(1, count("order_items"));
    }

    @Test
    void abortBeforeSnapshotKeepsKeyConsumedAndPreventsLateWrites() {
        var intent = start();
        var aborted = tx().requestAbort(intent.checkoutId(), CheckoutFailureReason.CART_NOT_FOUND);
        assertEquals(404, aborted.failureReason().httpStatus());
        assertStatus(409, () -> tx().prepared(intent.checkoutId(), cartId, List.of(new CheckoutIntentItem(productId, 2))));
        assertStatus(409, () -> tx().persistOrder(intent.checkoutId(), order(key)));
        var confirmed = tx().aborted(intent.checkoutId());
        assertNull(confirmed.nextAttemptAt());
        assertEquals(confirmed.updatedAt(), tx().aborted(intent.checkoutId()).updatedAt());
        assertEquals(intent.checkoutId(), start().checkoutId());
        assertEquals(CheckoutIntentStatus.ABORTED, start().status());
        assertEquals(1, count("orders"));
    }

    @Test
    void staleRetryCannotRegressAbortOrCompletion() {
        var intent = prepared();
        tx().requestAbort(intent.checkoutId(), CheckoutFailureReason.PRODUCT_CONFLICT);
        var state = tx().recordFailure(intent.checkoutId(), CheckoutIntentStatus.PREPARED,
                CheckoutRetryCategory.PERSISTENCE_FAILURE, Instant.now().plusSeconds(300));
        assertEquals(CheckoutIntentStatus.ABORT_PENDING, state.status());
        assertEquals(0, state.attemptCount());
        tx().aborted(intent.checkoutId());
        assertNull(tx().recordFailure(intent.checkoutId(), CheckoutIntentStatus.ABORTED,
                CheckoutRetryCategory.UPSTREAM_REJECTED, Instant.now()).nextAttemptAt());
    }

    @Test
    void mismatchedCandidateDoesNotCreateAnOrderOrConsumeIntent() {
        var intent = prepared();
        assertStatus(409, () -> tx().persistOrder(intent.checkoutId(), order(UUID.randomUUID())));
        var wrongItems = Order.create(customerId, key, "EUR", address());
        wrongItems.addItem(productId, "Product", BigDecimal.TEN, 3);
        assertStatus(409, () -> tx().persistOrder(intent.checkoutId(), wrongItems));
        assertEquals(CheckoutIntentStatus.PREPARED, tx().get(intent.checkoutId()).status());
        assertEquals(1, count("orders"));
    }

    @Test
    void failedIntentUpdateRollsBackOrderAndItems() throws Exception {
        var intent = prepared();
        execute("ALTER TABLE " + schema + ".checkout_intents ADD CONSTRAINT test_reject_commit CHECK (status <> 'COMPLETION_PENDING')");
        assertThrows(DataIntegrityViolationException.class, () -> tx().persistOrder(intent.checkoutId(), order(key)));
        assertEquals(CheckoutIntentStatus.PREPARED, tx().get(intent.checkoutId()).status());
        assertNull(tx().get(intent.checkoutId()).orderId());
        assertEquals(1, count("orders"));
        assertEquals(0, count("order_items"));
    }

    @Test
    void sourceCartUniquenessRollsBackTheLosingIntent() throws Exception {
        var first = prepared();
        tx().persistOrder(first.checkoutId(), order(key));
        var nextKey = UUID.randomUUID();
        var second = service().start(customerId, nextKey, address());
        tx().prepared(second.checkoutId(), cartId, List.of(new CheckoutIntentItem(productId, 2)));
        assertThrows(DataIntegrityViolationException.class, () -> tx().persistOrder(second.checkoutId(), order(nextKey)));
        assertEquals(CheckoutIntentStatus.PREPARED, tx().get(second.checkoutId()).status());
        assertNull(tx().get(second.checkoutId()).orderId());
        assertEquals(2, count("orders"));
    }

    @Test
    void concurrentCreationConvergesOnOneIntent() throws Exception {
        var results = race(this::start, this::start);
        assertEquals(((CheckoutIntentSnapshot) results.getFirst()).checkoutId(), ((CheckoutIntentSnapshot) results.getLast()).checkoutId());
        assertEquals(1, count("checkout_intents"));
    }

    @Test
    void concurrentAddressesCannotShareAnIntent() throws Exception {
        var results = race(() -> statusOrSuccess(this::start), () -> statusOrSuccess(() -> service().start(customerId, key,
                new ShippingAddress("Other", "Street", "Lisbon", "1000-001", "PT"))));
        assertTrue(results.contains(200));
        assertTrue(results.contains(409));
        assertEquals(1, count("checkout_intents"));
    }

    @Test
    void concurrentOrderPersistenceReturnsOneOrder() throws Exception {
        var intent = prepared();
        var results = race(() -> tx().persistOrder(intent.checkoutId(), order(key)),
                () -> tx().persistOrder(intent.checkoutId(), order(key)));
        assertEquals(((OrderResponse) results.getFirst()).id(), ((OrderResponse) results.getLast()).id());
        assertEquals(2, count("orders"));
        assertEquals(1, count("order_items"));
    }

    @Test
    void differentIntentsCannotConcurrentlyPersistTheSameCart() throws Exception {
        var first = prepared();
        var secondKey = UUID.randomUUID();
        var second = service().start(customerId, secondKey, address());
        tx().prepared(second.checkoutId(), cartId, List.of(new CheckoutIntentItem(productId, 2)));
        var results = race(() -> originRaceResult(first.checkoutId(), key),
                () -> originRaceResult(second.checkoutId(), secondKey));
        assertTrue(results.contains(200));
        assertTrue(results.contains(409));
        assertEquals(2, count("orders"));
        assertEquals(1, count("order_items"));
        var states = List.of(tx().get(first.checkoutId()).status(), tx().get(second.checkoutId()).status());
        assertTrue(states.contains(CheckoutIntentStatus.PREPARED));
        assertTrue(states.contains(CheckoutIntentStatus.COMPLETION_PENDING));
    }

    private int originRaceResult(UUID id, UUID orderKey) {
        try { tx().persistOrder(id, order(orderKey)); return 200; }
        catch (DataIntegrityViolationException expected) {
            for (Throwable cause = expected; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                    assertEquals("uk_orders_source_cart", violation.getConstraintName());
                    return 409;
                }
            }
            throw expected;
        }
    }

    @Test
    void abortAndOrderCommitCannotBothWin() throws Exception {
        var intent = prepared();
        var results = race(() -> statusOrSuccess(() -> tx().persistOrder(intent.checkoutId(), order(key))),
                () -> statusOrSuccess(() -> tx().requestAbort(intent.checkoutId(), CheckoutFailureReason.PRODUCT_CONFLICT)));
        assertTrue(results.contains(200));
        assertTrue(results.contains(409));
        var state = tx().get(intent.checkoutId());
        assertEquals(state.status() == CheckoutIntentStatus.COMPLETION_PENDING ? 2 : 1, count("orders"));
        assertEquals(state.status() == CheckoutIntentStatus.COMPLETION_PENDING, state.orderId() != null);
    }

    @Test
    void databaseEnforcesStateSnapshotAndLocalForeignKey() throws Exception {
        var intent = prepared();
        var predicate = " WHERE checkout_id = '" + intent.checkoutId() + "'";
        assertThrows(SQLException.class, () -> execute("UPDATE " + schema + ".checkout_intents SET status = 'COMPLETED'" + predicate));
        assertThrows(SQLException.class, () -> execute("UPDATE " + schema + ".checkout_intents SET attempt_count = -1" + predicate));
        assertThrows(SQLException.class, () -> execute("UPDATE " + schema + ".checkout_intents SET last_failure_category = 'raw upstream text'" + predicate));
        assertThrows(SQLException.class, () -> execute("UPDATE " + schema + ".checkout_intent_items SET quantity = 0" + predicate));
        assertThrows(SQLException.class, () -> execute("UPDATE " + schema + ".checkout_intents SET status = 'COMPLETION_PENDING', order_id = '"
                + UUID.randomUUID() + "'" + predicate));
        assertEquals(CheckoutIntentStatus.PREPARED, tx().get(intent.checkoutId()).status());
    }

    @Test
    void optimisticVersionRejectsStaleAggregate() {
        var id = start().checkoutId();
        var repository = context.getBean(CheckoutIntentRepository.class);
        var stale = repository.findById(id).orElseThrow();
        tx().requestAbort(id, CheckoutFailureReason.CART_NOT_FOUND);
        stale.requestAbort(CheckoutFailureReason.PRODUCT_CONFLICT);
        var transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> transaction.executeWithoutResult(status -> repository.saveAndFlush(stale)));
        assertEquals(CheckoutFailureReason.CART_NOT_FOUND, tx().get(id).failureReason());
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
        try { action.call(); return 200; }
        catch (ResponseStatusException failure) { return failure.getStatusCode().value(); }
    }
    private void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
    private int count(String table) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }
    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @Import({CheckoutIntentService.class, CheckoutIntentTransaction.class, CreateOrderTransaction.class})
    static class PersistenceConfig {
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }
}
