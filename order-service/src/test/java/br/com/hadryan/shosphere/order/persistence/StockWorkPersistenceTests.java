package br.com.hadryan.shosphere.order.persistence;

import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.model.*;
import br.com.hadryan.shosphere.order.model.Order;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.repository.*;
import br.com.hadryan.shosphere.order.service.impl.*;
import br.com.hadryan.shosphere.order.client.InventoryReservationClient;
import br.com.hadryan.shosphere.order.client.StockIntegrationFailure;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class StockWorkPersistenceTests {
    private DriverManagerDataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private String schema;
    private final UUID customer = UUID.randomUUID(), key = UUID.randomUUID(), cart = UUID.randomUUID(), product = UUID.randomUUID();
    private final UUID existingOrder = UUID.randomUUID();
    private final InventoryReservationClient inventory = mock(InventoryReservationClient.class);
    private final Instant created = Instant.parse("2026-10-30T12:00:00Z"), expires = created.plusSeconds(900);

    @BeforeEach void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        jdbc = new JdbcTemplate(dataSource);
        schema = "stock_work_test_" + UUID.randomUUID().toString().replace("-", "");
        flyway("4").migrate();
        jdbc.update("INSERT INTO " + schema + ".orders(id, customer_id, total_amount, subtotal) VALUES (?, ?, 25, 25)", existingOrder, customer);
        flyway(null).migrate();
        openContext();
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private void openContext() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("stock-flow-test", Map.of("checkout.snapshot.enabled", "true")));
        context.registerBean(InventoryReservationClient.class, () -> inventory);
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
        context.register(Config.class);
        context.refresh();
    }

    @AfterEach void tearDown() {
        if (context != null) context.close();
        if (schema == null || !schema.matches("stock_work_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test schema");
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private CheckoutIntentTransaction checkout() { return context.getBean(CheckoutIntentTransaction.class); }
    private StockWorkTransaction stock() { return context.getBean(StockWorkTransaction.class); }
    private StockReservationFlow flow() { return context.getBean(StockReservationFlow.class); }
    private ShippingAddress address() { return new ShippingAddress("Customer", "Street", null, "Lisbon", "1000-001", "PT"); }
    private CheckoutIntentSnapshot prepared() {
        var intent = checkout().start(customer, key, address());
        return checkout().prepared(intent.checkoutId(), cart, List.of(new CheckoutIntentItem(product, 2)));
    }
    private Order order() {
        var order = Order.create(customer, key, "EUR", address());
        order.addItem(product, "Financial snapshot", new BigDecimal("12.50"), 2);
        return order;
    }
    private OrderResponse persist() { return checkout().persistOrder(prepared().checkoutId(), order()); }
    private UUID ready() {
        var id = persist().id();
        checkout().completed(stock().get(id).checkoutId());
        stock().cartCompleted(id);
        return id;
    }
    private StockReservationObservation observation(UUID id, StockReservationStatus status) {
        return new StockReservationObservation(id, status, List.of(new StockWorkSnapshot.Item(product, 2)), created, expires,
                status == StockReservationStatus.ACTIVE ? created : expires);
    }
    private StockWorkSnapshot observe(UUID id, StockReservationStatus status) { return stock().observe(id, observation(id, status), expires); }
    private OrderResponse response(UUID id) {
        return context.getBean(br.com.hadryan.shosphere.order.service.GetOrderByIdUseCase.class).execute(customer, id);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + schema + "." + table, Long.class); }
    private void sql(String sql) { jdbc.execute(sql.replace("$schema", schema)); }

    @Test void upgradeDoesNotEnrollExistingOrPreviousCreationFlow() {
        assertEquals("5", flyway(null).info().current().getVersion().getVersion());
        assertEquals(0, flyway(null).migrate().migrationsExecuted);
        assertEquals(0, count("stock_work"));
        assertEquals(new BigDecimal("25.00"), response(existingOrder).totalAmount());
        var previous = context.getBean(CreateOrderTransaction.class).execute(order());
        assertEquals(OrderStatus.PENDING_STOCK, previous.status());
        assertEquals(0, count("stock_work"));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> stock().get(previous.id())).getStatusCode().value());
    }

    @Test void orderIntentAndWorkCommitTogetherReplayIsUniqueAndRestartRetainsSchedule() {
        var order = persist();
        var work = stock().get(order.id());
        assertEquals(StockWorkPhase.WAITING_CART, work.phase());
        assertEquals(CheckoutIntentStatus.COMPLETION_PENDING, checkout().get(work.checkoutId()).status());
        assertEquals(List.of(new StockWorkSnapshot.Item(product, 2)), work.items());
        assertEquals(order, checkout().persistOrder(work.checkoutId(), order()));
        assertEquals(1, count("stock_work"));
        var failed = stock().recordFailure(order.id(), StockWorkPhase.WAITING_CART,
                StockFailureCategory.AUTHENTICATION_CONFIGURATION, expires);
        context.close();
        openContext();
        assertEquals(failed, stock().get(order.id()));
        assertEquals(1, stock().get(order.id()).attemptCount());
        assertEquals(order, response(order.id()));
    }

    @Test void failedWorkInsertRollsBackOrderAndIntentAndCanBeRetried() {
        var intent = prepared();
        sql("ALTER TABLE $schema.stock_work ADD CONSTRAINT reject_work CHECK (phase <> 'WAITING_CART')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> checkout().persistOrder(intent.checkoutId(), order()));
        assertEquals(1, count("orders")); // Only the pre-existing V4 row.
        assertEquals(0, count("order_items"));
        assertEquals(0, count("stock_work"));
        assertEquals(CheckoutIntentStatus.PREPARED, checkout().get(intent.checkoutId()).status());
        assertNull(checkout().get(intent.checkoutId()).orderId());
        sql("ALTER TABLE $schema.stock_work DROP CONSTRAINT reject_work");
        var saved = checkout().persistOrder(intent.checkoutId(), order());
        assertEquals(StockWorkPhase.WAITING_CART, stock().get(saved.id()).phase());
    }

    @Test void completionEligibilityIsRecoveredWithoutCallbackAfterRestart() {
        var id = persist().id();
        assertEquals(StockWorkPhase.WAITING_CART, stock().cartCompleted(id).phase());
        checkout().completed(stock().get(id).checkoutId());
        context.close(); openContext();
        assertEquals(StockWorkPhase.WAITING_CART, stock().get(id).phase());
        var ready = stock().cartCompleted(id);
        assertEquals(StockWorkPhase.RESERVE_PENDING, ready.phase());
        assertEquals(ready, stock().cartCompleted(id));
        assertEquals(OrderStatus.PENDING_STOCK, response(id).status());
    }

    @Test void activeAndTerminalTransitionsUpdatePublicStateWithoutChangingFinancialSnapshot() {
        var id = ready();
        var before = response(id);
        var original = context.getBean(OrderRepository.class).findById(id).orElseThrow();
        var active = observe(id, StockReservationStatus.ACTIVE);
        assertEquals(StockWorkPhase.RESERVED, active.phase());
        assertEquals(expires, active.reservationExpiresAt());
        context.close(); openContext();
        assertEquals(active, stock().get(id));
        assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
        assertNull(response(id).paymentStatus());
        var terminal = observe(id, StockReservationStatus.EXPIRED);
        assertEquals(StockWorkPhase.CLOSED, terminal.phase());
        assertEquals(StockWorkReason.RESERVATION_EXPIRED, terminal.reason());
        assertNull(terminal.nextAttemptAt());
        assertEquals(OrderStatus.CANCELLED, response(id).status());
        var after = response(id);
        assertEquals(before.items(), after.items());
        assertEquals(before.totalAmount(), after.totalAmount());
        assertEquals(before.currency(), after.currency());
        var saved = context.getBean(OrderRepository.class).findById(id).orElseThrow();
        assertEquals(original.getCreatedAt(), saved.getCreatedAt());
        assertEquals(original.getShippingAddress().getAddressLine(), saved.getShippingAddress().getAddressLine());
        assertEquals(cart, saved.getSourceCartId());
        assertEquals(terminal, observe(id, StockReservationStatus.ACTIVE));
        assertEquals(after, checkout().persistOrder(terminal.checkoutId(), order()));
    }

    @Test void elapsedLocalDeadlineDoesNotCancelOrConsumeStock() {
        var id = ready();
        observe(id, StockReservationStatus.ACTIVE); // Observation expires in the past relative to the test runtime.
        assertEquals(StockWorkPhase.RESERVED, stock().get(id).phase());
        assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
        stock().recordFailure(id, StockWorkPhase.RESERVED, StockFailureCategory.UPSTREAM_UNAVAILABLE, Instant.now().plusSeconds(60));
        assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
    }

    @Test void abortRequiresProofKeepsDecisionAcrossRestartAndRejectsLateActive() {
        var id = ready();
        var pending = stock().requestAbort(id, StockWorkReason.RESERVATION_REJECTED);
        assertEquals(OrderStatus.PENDING_STOCK, response(id).status());
        assertEquals(pending, stock().requestAbort(id, StockWorkReason.RESERVATION_EXPIRED));
        context.close(); openContext();
        assertEquals(pending, observe(id, StockReservationStatus.ACTIVE));
        var closed = stock().aborted(id, id, null);
        assertEquals(StockWorkPhase.CLOSED, closed.phase());
        assertTrue(closed.abortRequested());
        assertEquals(OrderStatus.CANCELLED, response(id).status());
        assertEquals(closed, stock().aborted(id, id, null));
        assertEquals(closed, observe(id, StockReservationStatus.ACTIVE));
    }

    @Test void nullAbortProofAfterKnownActiveRequiresReviewWithoutFalseCancellation() {
        var id = ready(); observe(id, StockReservationStatus.ACTIVE);
        stock().requestAbort(id, StockWorkReason.RESERVATION_EXPIRED);
        var review = stock().aborted(id, id, null);
        assertEquals(StockWorkPhase.REVIEW_REQUIRED, review.phase());
        assertTrue(review.abortRequested());
        assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
        assertEquals(review, observe(id, StockReservationStatus.ACTIVE));
    }

    @Test void existingReservationAbortProofClosesTogetherWithPublicCancellation() {
        var id = ready(); observe(id, StockReservationStatus.ACTIVE);
        stock().requestAbort(id, StockWorkReason.RESERVATION_EXPIRED);
        assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
        assertEquals(StockWorkPhase.CLOSED, stock().aborted(id, id, observation(id, StockReservationStatus.RELEASED)).phase());
        assertEquals(OrderStatus.CANCELLED, response(id).status());
    }

    @ParameterizedTest @ValueSource(strings = {"identity", "items", "duplicates", "dates", "renewal", "confirmed"})
    void invalidOrUnexpectedObservationRequiresReviewWithoutBusinessSuccess(String invalid) {
        var id = ready();
        if (invalid.equals("renewal")) observe(id, StockReservationStatus.ACTIVE);
        var good = observation(id, StockReservationStatus.ACTIVE);
        var bad = switch (invalid) {
            case "identity" -> new StockReservationObservation(UUID.randomUUID(), good.status(), good.items(), created, expires, created);
            case "items" -> new StockReservationObservation(id, good.status(), List.of(new StockWorkSnapshot.Item(product, 3)), created, expires, created);
            case "duplicates" -> new StockReservationObservation(id, good.status(), List.of(good.items().getFirst(), good.items().getFirst()), created, expires, created);
            case "dates" -> new StockReservationObservation(id, good.status(), good.items(), expires, created, created);
            case "renewal" -> new StockReservationObservation(id, good.status(), good.items(), created, expires.plusSeconds(1), created);
            default -> observation(id, StockReservationStatus.CONFIRMED);
        };
        assertEquals(StockWorkPhase.REVIEW_REQUIRED, stock().observe(id, bad, expires).phase());
        assertEquals(invalid.equals("renewal") ? OrderStatus.PENDING_PAYMENT : OrderStatus.PENDING_STOCK, response(id).status());
        assertNotEquals(OrderStatus.CONFIRMED, response(id).status());
    }

    @Test void localCommitFailureRollsBackWorkObservationAndPublicStatusTogether() {
        var id = ready();
        var before = stock().get(id);
        sql("ALTER TABLE $schema.orders ADD CONSTRAINT reject_payment CHECK (status <> 'PENDING_PAYMENT')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> observe(id, StockReservationStatus.ACTIVE));
        assertEquals(before, stock().get(id));
        assertEquals(OrderStatus.PENDING_STOCK, response(id).status());
        sql("ALTER TABLE $schema.orders DROP CONSTRAINT reject_payment");
        observe(id, StockReservationStatus.ACTIVE);
        before = stock().get(id);
        sql("ALTER TABLE $schema.stock_work ADD CONSTRAINT reject_closed CHECK (phase <> 'CLOSED')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> observe(id, StockReservationStatus.EXPIRED));
        assertEquals(before, stock().get(id));
        assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
    }

    @Test void concurrentOrderReplayCreatesOnlyOneWork() throws Exception {
        var intent = prepared();
        race(() -> checkout().persistOrder(intent.checkoutId(), order()), () -> checkout().persistOrder(intent.checkoutId(), order()));
        assertEquals(1, count("stock_work"));
        assertEquals(2, count("orders"));
    }

    @Test void concurrentAbortAndActiveCannotReverseDurableDecision() throws Exception {
        var id = ready();
        race(() -> stock().requestAbort(id, StockWorkReason.RESERVATION_REJECTED), () -> observe(id, StockReservationStatus.ACTIVE));
        var work = stock().get(id);
        assertTrue(work.abortRequested());
        assertEquals(StockWorkPhase.ABORT_PENDING, work.phase());
        observe(id, StockReservationStatus.ACTIVE);
        assertEquals(StockWorkPhase.ABORT_PENDING, stock().get(id).phase());
        race(() -> stock().aborted(id, id, observation(id, StockReservationStatus.RELEASED)), () -> observe(id, StockReservationStatus.ACTIVE));
        assertEquals(StockWorkPhase.CLOSED, stock().get(id).phase());
        assertEquals(OrderStatus.CANCELLED, response(id).status());
    }

    @Test void sqlRejectsInvalidStatesUniquenessAndBrokenReferencesAndVersionProtectsDetachedChanges() {
        var id = ready();
        for (var change : List.of("attempt_count = -1", "version = -1", "phase = 'UNKNOWN'", "phase = 'RESERVED'",
                "phase = 'CLOSED', next_attempt_at = NULL", "phase = 'ABORT_PENDING'", "next_attempt_at = NULL",
                "last_failure_category = 'secret'", "reservation_status = 'ACTIVE'", "abort_requested = TRUE"))
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> sql("UPDATE $schema.stock_work SET " + change + " WHERE order_id = '" + id + "'"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> sql("UPDATE $schema.stock_work SET order_id = '" + UUID.randomUUID() + "'"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> sql("UPDATE $schema.stock_work SET checkout_id = '" + UUID.randomUUID() + "'"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> sql("INSERT INTO $schema.stock_work SELECT * FROM $schema.stock_work"));
        var repository = context.getBean(StockWorkRepository.class);
        var old = repository.findById(id).orElseThrow();
        stock().requestAbort(id, StockWorkReason.RESERVATION_REJECTED);
        old.requestAbort(StockWorkReason.RESERVATION_EXPIRED);
        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class, () -> repository.saveAndFlush(old));
        assertEquals(StockWorkReason.RESERVATION_REJECTED, stock().get(id).reason());
    }

    @Test void hundredItemsUsePersistedOrderAsSingleImmutableIntent() {
        var order = Order.create(customer, key, "EUR", address());
        for (int i = 0; i < 100; i++) order.addItem(UUID.randomUUID(), "Product", BigDecimal.ONE, 1);
        var intent = checkout().start(customer, key, address());
        checkout().prepared(intent.checkoutId(), cart, order.getItems().stream()
                .map(i -> new CheckoutIntentItem(i.getProductId(), i.getQuantity())).toList());
        var saved = checkout().persistOrder(intent.checkoutId(), order);
        assertEquals(100, stock().get(saved.id()).items().size());
        assertThrows(UnsupportedOperationException.class, () -> stock().get(saved.id()).items().clear());
        assertEquals(new BigDecimal("100.00"), response(saved.id()).totalAmount());
    }

    private void race(Callable<?> first, Callable<?> second) throws Exception {
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> { gate.await(10, TimeUnit.SECONDS); return action.call(); })).toList();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }
    }

    private StockReservationObservation active(UUID id) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Inventory HTTP must not run under a database transaction");
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new StockReservationObservation(id, StockReservationStatus.ACTIVE,
                List.of(new StockWorkSnapshot.Item(product, 2)), now, now.plusSeconds(900), now);
    }
    private StockIntegrationFailure missing() { return new StockIntegrationFailure(404, StockFailureCategory.INVALID_UPSTREAM_RESPONSE); }

    @Test void flowWaitsForDurableCartCompletionThenReservesOutsideEvenAnOuterTransaction() {
        var id = persist().id();
        assertEquals(StockWorkPhase.WAITING_CART, flow().resume(id).phase());
        verifyNoInteractions(inventory);
        checkout().completed(stock().get(id).checkoutId());
        when(inventory.get(any())).thenAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); throw missing(); });
        when(inventory.reserve(any())).thenAnswer(call -> {
            var work = call.getArgument(0, StockWorkSnapshot.class);
            assertEquals(id, work.orderId());
            assertEquals(List.of(new StockWorkSnapshot.Item(product, 2)), work.items());
            return active(id);
        });
        new TransactionTemplate(context.getBean(JpaTransactionManager.class)).execute(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(StockWorkPhase.RESERVED, flow().resume(id).phase());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        });
        assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
        verify(inventory).reserve(any()); verify(inventory, never()).abort(any());
    }

    @Test void lostPutResponseIsRecoveredByGetAfterRestartWithoutAnotherReservationIntent() {
        var id = ready();
        var remote = active(id);
        when(inventory.get(any())).thenThrow(missing()).thenReturn(remote);
        when(inventory.reserve(any())).thenThrow(new StockIntegrationFailure(0, StockFailureCategory.UPSTREAM_UNAVAILABLE));
        var pending = flow().resume(id);
        assertEquals(StockWorkPhase.RESERVE_PENDING, pending.phase());
        assertEquals(1, pending.attemptCount());
        assertEquals(OrderStatus.PENDING_STOCK, response(id).status());
        context.close(); openContext();
        assertEquals(StockWorkPhase.RESERVED, flow().resume(id).phase());
        verify(inventory).reserve(any()); verify(inventory, never()).abort(any());
        assertEquals(1, count("stock_work"));
    }

    @ParameterizedTest @ValueSource(ints = {404, 409})
    void rejectedPutAndAbsentGetCancelOnlyAfterDurableAbortProof(int code) {
        var id = ready();
        when(inventory.get(any())).thenThrow(missing());
        when(inventory.reserve(any())).thenThrow(new StockIntegrationFailure(code, StockFailureCategory.INVALID_UPSTREAM_RESPONSE));
        when(inventory.abort(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(StockWorkPhase.ABORT_PENDING, stock().get(id).phase());
            assertEquals(OrderStatus.PENDING_STOCK, response(id).status());
            return new InventoryReservationClient.AbortProof(id, null);
        });
        var closed = flow().resume(id);
        assertEquals(StockWorkPhase.CLOSED, closed.phase());
        assertEquals(StockWorkReason.RESERVATION_REJECTED, closed.reason());
        assertEquals(OrderStatus.CANCELLED, response(id).status());
        assertEquals(CheckoutIntentStatus.COMPLETED, checkout().get(closed.checkoutId()).status());
        verify(inventory, times(2)).get(any()); verify(inventory).abort(any());
    }

    @Test void concurrentWinningReservationAfterPutRejectionIsAcceptedInsteadOfAborted() {
        var id = ready();
        when(inventory.get(any())).thenThrow(missing()).thenReturn(active(id));
        when(inventory.reserve(any())).thenThrow(new StockIntegrationFailure(409, StockFailureCategory.INVALID_UPSTREAM_RESPONSE));
        assertEquals(StockWorkPhase.RESERVED, flow().resume(id).phase());
        verify(inventory, never()).abort(any());
    }

    @ParameterizedTest @ValueSource(strings = {"AUTHENTICATION_CONFIGURATION", "UPSTREAM_UNAVAILABLE"})
    void technicalFailuresPreservePhaseAndPublicState(String category) {
        var id = ready();
        when(inventory.get(any())).thenThrow(new StockIntegrationFailure(0, StockFailureCategory.valueOf(category)));
        var pending = flow().resume(id);
        assertEquals(StockWorkPhase.RESERVE_PENDING, pending.phase());
        assertEquals(StockFailureCategory.valueOf(category), pending.lastFailureCategory());
        assertEquals(1, pending.attemptCount());
        assertEquals(OrderStatus.PENDING_STOCK, response(id).status());
        verify(inventory, never()).reserve(any()); verify(inventory, never()).abort(any());
    }

    @Test void expirationRequestsAbortAndInventoryDecidesTerminalState() {
        var id = ready();
        when(inventory.get(any())).thenReturn(observation(id, StockReservationStatus.ACTIVE));
        when(inventory.abort(any())).thenAnswer(call -> {
            assertEquals(StockWorkPhase.ABORT_PENDING, stock().get(id).phase());
            assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
            return new InventoryReservationClient.AbortProof(id, observation(id, StockReservationStatus.RELEASED));
        });
        assertEquals(StockWorkPhase.CLOSED, flow().resume(id).phase());
        assertEquals(OrderStatus.CANCELLED, response(id).status());
        assertEquals(StockReservationStatus.RELEASED, stock().get(id).reservationStatus());
        verify(inventory, never()).reserve(any());
    }

    @Test void abortTimeoutSurvivesRestartAndNeverReissuesPut() {
        var id = ready();
        stock().requestAbort(id, StockWorkReason.RESERVATION_REJECTED);
        when(inventory.abort(any())).thenThrow(new StockIntegrationFailure(0, StockFailureCategory.UPSTREAM_UNAVAILABLE))
                .thenReturn(new InventoryReservationClient.AbortProof(id, null));
        assertEquals(StockWorkPhase.ABORT_PENDING, flow().resume(id).phase());
        assertEquals(1, stock().get(id).attemptCount());
        assertEquals(OrderStatus.PENDING_STOCK, response(id).status());
        context.close(); openContext();
        assertEquals(StockWorkPhase.CLOSED, flow().resume(id).phase());
        verify(inventory, never()).reserve(any()); verify(inventory, never()).get(any());
    }

    @Test void abortConflictRequiresReviewAndPreservesCancellationDecision() {
        var id = ready(); stock().requestAbort(id, StockWorkReason.RESERVATION_REJECTED);
        when(inventory.abort(any())).thenThrow(new StockIntegrationFailure(409, StockFailureCategory.INVALID_UPSTREAM_RESPONSE));
        var review = flow().resume(id);
        assertEquals(StockWorkPhase.REVIEW_REQUIRED, review.phase());
        assertTrue(review.abortRequested());
        assertEquals(OrderStatus.PENDING_STOCK, response(id).status());
        verify(inventory, never()).reserve(any());
    }

    @Test void invalidResponseAndUnexpectedConfirmedNeverBecomeBusinessSuccess() {
        var id = ready();
        when(inventory.get(any())).thenReturn(observation(id, StockReservationStatus.CONFIRMED));
        assertEquals(StockWorkPhase.REVIEW_REQUIRED, flow().resume(id).phase());
        assertEquals(OrderStatus.PENDING_STOCK, response(id).status());
        when(inventory.get(any())).thenThrow(new StockIntegrationFailure(400, StockFailureCategory.INVALID_UPSTREAM_RESPONSE));
        assertEquals(StockWorkPhase.REVIEW_REQUIRED, flow().resume(id).phase());
        verify(inventory, never()).reserve(any()); verify(inventory, never()).abort(any());
    }

    @Test void responseAfterDurableAbortCannotPromoteOrderAndMustConfirmAbort() {
        var id = ready();
        when(inventory.get(any())).thenThrow(missing());
        when(inventory.reserve(any())).thenAnswer(call -> {
            stock().requestAbort(id, StockWorkReason.RESERVATION_REJECTED);
            return active(id);
        });
        when(inventory.abort(any())).thenReturn(new InventoryReservationClient.AbortProof(id, observation(id, StockReservationStatus.RELEASED)));
        assertEquals(StockWorkPhase.CLOSED, flow().resume(id).phase());
        assertEquals(OrderStatus.CANCELLED, response(id).status());
        assertTrue(stock().get(id).abortRequested());
    }

    @Test void durableDecisionBetweenGetAndPutPreventsStartingPut() {
        var id = ready();
        when(inventory.get(any())).thenAnswer(call -> {
            stock().requestAbort(id, StockWorkReason.RESERVATION_REJECTED);
            throw missing();
        });
        assertEquals(StockWorkPhase.ABORT_PENDING, flow().resume(id).phase());
        verify(inventory, never()).reserve(any());
        assertEquals(OrderStatus.PENDING_STOCK, response(id).status());
    }

    @Test void failedLocalCommitAfterRemoteSuccessIsRecoveredWithoutNewPut() {
        var id = ready();
        var remote = active(id);
        when(inventory.get(any())).thenReturn(remote);
        sql("ALTER TABLE $schema.orders ADD CONSTRAINT reject_flow_payment CHECK (status <> 'PENDING_PAYMENT')");
        var pending = flow().resume(id);
        assertEquals(StockWorkPhase.RESERVE_PENDING, pending.phase());
        assertEquals(StockFailureCategory.PERSISTENCE_FAILURE, pending.lastFailureCategory());
        sql("ALTER TABLE $schema.orders DROP CONSTRAINT reject_flow_payment");
        assertEquals(StockWorkPhase.RESERVED, flow().resume(id).phase());
        verify(inventory, never()).reserve(any());
    }

    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @Import({CheckoutIntentTransaction.class, StockWorkTransaction.class, StockReservationFlow.class, CreateOrderTransaction.class, GetOrderByIdUseCaseImpl.class})
    static class Config {
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }
}
