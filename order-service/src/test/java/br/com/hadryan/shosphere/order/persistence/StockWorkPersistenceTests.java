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
import java.time.Duration;
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
    private final Map<String, Object> operationalProperties = new HashMap<>();
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
        var properties = new HashMap<String, Object>(operationalProperties);
        properties.put("checkout.snapshot.enabled", "true");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("stock-flow-test", properties));
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
        assertEquals("8", flyway(null).info().current().getVersion().getVersion());
        assertEquals(0, flyway(null).migrate().migrationsExecuted);
        assertEquals(0, count("stock_work"));
        assertEquals(0, count("payment_work"));
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
                "last_failure_category = 'secret'", "reservation_status = 'ACTIVE'", "abort_requested = TRUE",
                "claim_token = '" + UUID.randomUUID() + "'", "claimed_until = CURRENT_TIMESTAMP"))
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

    private StockRecoveryJob recovery(int batch) {
        return new StockRecoveryJob(stock(), flow(), batch, Duration.ofSeconds(60), Duration.ofMinutes(15),
                Duration.ofSeconds(60), Duration.ofSeconds(60));
    }

    @Test void upgradeV5PreservesExistingWorkAndAddsUnclaimedLeaseAndWarningMetadata() {
        var previousSchema = schema;
        var upgradeSchema = "stock_upgrade_test_" + UUID.randomUUID().toString().replace("-", "");
        var id = UUID.randomUUID(); var checkoutId = UUID.randomUUID();
        context.close();
        try {
            Flyway.configure().dataSource(dataSource).schemas(upgradeSchema).defaultSchema(upgradeSchema)
                    .locations("classpath:db/migration").target("5").load().migrate();
            jdbc.update("INSERT INTO " + upgradeSchema + ".orders(id,customer_id,source_cart_id,total_amount,subtotal) VALUES (?,?,?,25,25)", id, customer, cart);
            jdbc.update("INSERT INTO " + upgradeSchema + ".order_items(id,order_id,product_id,product_name,quantity,unit_price,line_total) VALUES (?,?,?,'Snapshot',2,12.50,25)",
                    UUID.randomUUID(), id, product);
            jdbc.update("INSERT INTO " + upgradeSchema + ".checkout_intents(checkout_id,customer_id,idempotency_key,status,"
                    + "shipping_recipient,shipping_address_line,shipping_city,shipping_postal_code,shipping_country_code,"
                    + "cart_id,order_id,created_at,updated_at) VALUES (?,?,?,'COMPLETED','Customer','Street','Lisbon','1000-001','PT',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    checkoutId, customer, key.toString(), cart, id);
            jdbc.update("INSERT INTO " + upgradeSchema + ".checkout_intent_items(checkout_id,product_id,quantity) VALUES (?,?,2)", checkoutId, product);
            jdbc.update("INSERT INTO " + upgradeSchema + ".stock_work(order_id,checkout_id,phase,abort_requested,reason,"
                    + "next_attempt_at,attempt_count,created_at,updated_at) VALUES (?,?,'ABORT_PENDING',true,'RESERVATION_REJECTED',CURRENT_TIMESTAMP,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, checkoutId);
            var upgraded = Flyway.configure().dataSource(dataSource).schemas(upgradeSchema).defaultSchema(upgradeSchema)
                    .locations("classpath:db/migration").load();
            assertEquals(3, upgraded.migrate().migrationsExecuted);
            assertTrue(upgraded.validateWithResult().validationSuccessful);
            schema = upgradeSchema; openContext(); // Validate new entity columns against the upgraded schema.
            var work = stock().get(id);
            assertEquals(StockWorkPhase.ABORT_PENDING, work.phase());
            assertTrue(work.abortRequested()); assertEquals(3, work.attemptCount());
            assertEquals(StockWorkReason.RESERVATION_REJECTED, work.reason());
            assertEquals(List.of(new StockWorkSnapshot.Item(product, 2)), work.items());
            assertEquals(new BigDecimal("25.00"), response(id).totalAmount());
            var metadata = jdbc.queryForMap("SELECT claim_token,claimed_until,last_warning_at FROM " + upgradeSchema + ".stock_work WHERE order_id = ?", id);
            assertTrue(metadata.values().stream().allMatch(Objects::isNull));
            assertTrue(stock().claim(id, Instant.now(), Duration.ofSeconds(60)).isPresent());
        } finally {
            if (context != null) context.close();
            schema = previousSchema;
            if (!upgradeSchema.matches("stock_upgrade_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid upgrade test schema");
            jdbc.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");
        }
    }
    private void makeDue(UUID id) { sql("UPDATE $schema.stock_work SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE order_id = '" + id + "'"); }
    private UUID otherReady() {
        var anotherKey = UUID.randomUUID();
        var intent = checkout().start(customer, anotherKey, address());
        checkout().prepared(intent.checkoutId(), UUID.randomUUID(), List.of(new CheckoutIntentItem(product, 2)));
        var order = Order.create(customer, anotherKey, "EUR", address());
        order.addItem(product, "Other snapshot", new BigDecimal("12.50"), 2);
        var id = checkout().persistOrder(intent.checkoutId(), order).id();
        checkout().completed(intent.checkoutId()); stock().cartCompleted(id);
        return id;
    }

    @Test void durableClaimIsIndependentOfAgendaAndSurvivesRestartWithFencedCompletion() {
        var id = ready(); var now = Instant.now();
        var agenda = stock().get(id).nextAttemptAt();
        var first = stock().claim(id, now, Duration.ofSeconds(60)).orElseThrow();
        assertEquals(agenda, stock().get(id).nextAttemptAt());
        assertTrue(stock().due(now.plusSeconds(1), 50).isEmpty());
        assertTrue(stock().claim(id, now.plusSeconds(1), Duration.ofSeconds(60)).isEmpty());
        context.close(); openContext();
        assertTrue(stock().due(now.plusSeconds(1), 50).isEmpty());
        var next = stock().claim(id, now.plusSeconds(61), Duration.ofSeconds(60)).orElseThrow();
        assertNotEquals(first.token(), next.token());
        assertTrue(stock().finish(first, true, now.plusSeconds(62), Duration.ofMinutes(15)).isEmpty());
        assertEquals(next.token(), jdbc.queryForObject("SELECT claim_token FROM " + schema + ".stock_work WHERE order_id = ?", UUID.class, id));
        var result = stock().finish(next, false, now.plusSeconds(62), Duration.ofMinutes(15)).orElseThrow();
        assertEquals(0, result.work().attemptCount());
        assertNull(jdbc.queryForObject("SELECT claim_token FROM " + schema + ".stock_work WHERE order_id = ?", UUID.class, id));
        assertTrue(stock().due(now.plusSeconds(63), 50).isEmpty());
    }

    @Test void concurrentClaimsHaveOneWinnerAndSkipLockedRowsAllowOtherWork() throws Exception {
        var id = ready(); var other = otherReady();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT order_id FROM " + schema + ".stock_work WHERE order_id = '" + id + "' FOR UPDATE");
            try {
                assertEquals(List.of(other), stock().due(Instant.now().plusSeconds(1), 1));
                assertTrue(stock().claim(id, Instant.now(), Duration.ofSeconds(60)).isEmpty());
                assertTrue(stock().claim(other, Instant.now(), Duration.ofSeconds(60)).isPresent());
            } finally { connection.rollback(); }
        }
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(n -> executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return stock().claim(id, Instant.now(), Duration.ofSeconds(60));
            })).toList();
            int winners = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS).isPresent()) winners++;
            assertEquals(1, winners);
        }
    }

    @Test void workerWaitsForCartAndRecoversEligibilityAfterRestartWithoutPublicReplay() {
        var id = persist().id();
        recovery(50).runOnce();
        assertEquals(StockWorkPhase.WAITING_CART, stock().get(id).phase());
        assertTrue(stock().get(id).nextAttemptAt().isAfter(Instant.now()));
        verifyNoInteractions(inventory);
        checkout().completed(stock().get(id).checkoutId());
        context.close(); openContext(); makeDue(id);
        when(inventory.get(any())).thenThrow(missing());
        when(inventory.reserve(any())).thenAnswer(call -> active(id));
        recovery(50).runOnce();
        assertEquals(StockWorkPhase.RESERVED, stock().get(id).phase());
        assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
        verify(inventory).reserve(any());
    }

    @Test void retryBackoffIsDurableCappedAndNeverAbandonsWork() {
        var id = ready();
        for (int seconds : new int[]{60, 120, 240, 480, 900, 900}) {
            var before = Instant.now();
            var state = stock().retry(id, StockWorkPhase.RESERVE_PENDING, StockFailureCategory.UPSTREAM_UNAVAILABLE);
            assertFalse(state.nextAttemptAt().isBefore(before.plusSeconds(seconds).minusMillis(1)));
            assertTrue(state.nextAttemptAt().isBefore(Instant.now().plusSeconds(seconds + 1)));
            assertEquals(StockWorkPhase.RESERVE_PENDING, state.phase());
        }
        assertEquals(6, stock().get(id).attemptCount());
        context.close(); openContext();
        assertEquals(6, stock().get(id).attemptCount());
        assertTrue(stock().due(Instant.now(), 50).isEmpty());
        makeDue(id); when(inventory.get(any())).thenAnswer(call -> active(id));
        recovery(50).runOnce();
        assertEquals(StockWorkPhase.RESERVED, stock().get(id).phase());
        assertEquals(6, stock().get(id).attemptCount());
        assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
    }

    @Test void monitorUsesRemoteDeadlineAndCapsTechnicalFailureDelayAtSixtySeconds() {
        var id = ready();
        var remote = active(id);
        var near = new StockReservationObservation(id, remote.status(), remote.items(), remote.createdAt(), remote.createdAt().plusSeconds(30), remote.updatedAt());
        when(inventory.get(any())).thenReturn(near);
        recovery(50).runOnce();
        assertEquals(near.expiresAt(), stock().get(id).nextAttemptAt());
        assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
        when(inventory.get(any())).thenThrow(new StockIntegrationFailure(0, StockFailureCategory.UPSTREAM_UNAVAILABLE));
        for (int n = 0; n < 5; n++) {
            makeDue(id); recovery(50).runOnce();
            assertEquals(StockWorkPhase.RESERVED, stock().get(id).phase());
            assertTrue(stock().get(id).nextAttemptAt().isBefore(Instant.now().plusSeconds(61)));
        }
        assertEquals(5, stock().get(id).attemptCount());
        verify(inventory, never()).abort(any());
    }

    @Test void resumedWorkerAbortsExpiredObservationAndRetriesLostAbortProofAfterRestart() {
        var id = ready(); observe(id, StockReservationStatus.ACTIVE);
        when(inventory.get(any())).thenReturn(observation(id, StockReservationStatus.ACTIVE));
        when(inventory.abort(any())).thenThrow(new StockIntegrationFailure(0, StockFailureCategory.UPSTREAM_UNAVAILABLE))
                .thenReturn(new InventoryReservationClient.AbortProof(id, observation(id, StockReservationStatus.EXPIRED)));
        makeDue(id); recovery(50).runOnce();
        assertEquals(StockWorkPhase.ABORT_PENDING, stock().get(id).phase());
        assertEquals(OrderStatus.PENDING_PAYMENT, response(id).status());
        assertEquals(1, stock().get(id).attemptCount());
        context.close(); openContext(); makeDue(id); recovery(50).runOnce();
        assertEquals(StockWorkPhase.CLOSED, stock().get(id).phase());
        assertEquals(OrderStatus.CANCELLED, response(id).status());
        assertTrue(stock().due(Instant.now().plusSeconds(10000), 50).isEmpty());
        verify(inventory, never()).reserve(any());
        verify(inventory, times(1)).get(any());
    }

    @Test void failedFirstWorkDoesNotStarveLaterBatchesAndFutureAttemptsAreSkipped() {
        var id = ready(); var other = otherReady();
        when(inventory.get(any())).thenAnswer(call -> {
            var work = call.getArgument(0, StockWorkSnapshot.class);
            if (work.orderId().equals(id)) throw new StockIntegrationFailure(0, StockFailureCategory.AUTHENTICATION_CONFIGURATION);
            return active(other);
        });
        recovery(1).runOnce(); recovery(1).runOnce();
        assertEquals(1, stock().get(id).attemptCount());
        assertEquals(StockWorkPhase.RESERVE_PENDING, stock().get(id).phase());
        assertEquals(StockWorkPhase.RESERVED, stock().get(other).phase());
        assertTrue(stock().due(Instant.now(), 50).isEmpty());
        verify(inventory, times(2)).get(any());
    }

    @Test void reviewQueriesAndOverdueWarningsAreLimitedAcrossRestartWithoutLosingAbortDecision() {
        var id = ready(); stock().requestAbort(id, StockWorkReason.RESERVATION_REJECTED);
        stock().review(id, StockFailureCategory.INVALID_UPSTREAM_RESPONSE);
        sql("UPDATE $schema.stock_work SET created_at = CURRENT_TIMESTAMP - INTERVAL '20 minutes' WHERE order_id = '" + id + "'");
        var now = Instant.now();
        var first = stock().claim(id, now, Duration.ofSeconds(60)).orElseThrow();
        var warning = stock().finish(first, false, now, Duration.ofMinutes(15)).orElseThrow();
        assertTrue(warning.warnOverdue());
        assertEquals(StockWorkPhase.REVIEW_REQUIRED, warning.work().phase());
        assertTrue(warning.work().abortRequested());
        assertTrue(warning.work().nextAttemptAt().isAfter(now.plusSeconds(899)));
        context.close(); openContext(); makeDue(id);
        var second = stock().claim(id, now.plusSeconds(1), Duration.ofSeconds(60)).orElseThrow();
        assertFalse(stock().finish(second, false, now.plusSeconds(1), Duration.ofMinutes(15)).orElseThrow().warnOverdue());
        var later = stock().claim(id, now.plusSeconds(902), Duration.ofSeconds(60)).orElseThrow();
        assertTrue(stock().finish(later, false, now.plusSeconds(902), Duration.ofMinutes(15)).orElseThrow().warnOverdue());
        makeDue(id); when(inventory.get(any())).thenAnswer(call -> active(id));
        recovery(50).runOnce();
        assertEquals(StockWorkPhase.REVIEW_REQUIRED, stock().get(id).phase());
        verify(inventory, never()).reserve(any()); verify(inventory, never()).abort(any());
        assertEquals(OrderStatus.PENDING_STOCK, response(id).status());
    }

    @Test void bookkeepingFailureLeavesLeaseAndFallbackDoesNotDoubleCountRecordedFailure() {
        var id = ready(); var now = Instant.now();
        var claim = stock().claim(id, now, Duration.ofSeconds(60)).orElseThrow();
        sql("ALTER TABLE $schema.stock_work ADD CONSTRAINT reject_release CHECK (claim_token IS NOT NULL)");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> stock().finish(claim, true, now, Duration.ofMinutes(15)));
        assertEquals(0, stock().get(id).attemptCount());
        assertTrue(stock().claim(id, now.plusSeconds(1), Duration.ofSeconds(60)).isEmpty());
        sql("ALTER TABLE $schema.stock_work DROP CONSTRAINT reject_release");
        var next = stock().claim(id, now.plusSeconds(61), Duration.ofSeconds(60)).orElseThrow();
        var fallback = stock().finish(next, true, now.plusSeconds(61), Duration.ofMinutes(15)).orElseThrow();
        assertEquals(1, fallback.work().attemptCount());
        assertEquals(StockFailureCategory.PERSISTENCE_FAILURE, fallback.work().lastFailureCategory());
        makeDue(id); var another = stock().claim(id, Instant.now(), Duration.ofSeconds(60)).orElseThrow();
        stock().retry(id, StockWorkPhase.RESERVE_PENDING, StockFailureCategory.UPSTREAM_UNAVAILABLE);
        assertEquals(2, stock().finish(another, true, Instant.now(), Duration.ofMinutes(15)).orElseThrow().work().attemptCount());
    }

    @ParameterizedTest @ValueSource(strings = {"initial", "maximum", "monitor"})
    void invalidDurableSchedulingConfigurationFailsStartup(String invalid) {
        context.close();
        switch (invalid) {
            case "initial" -> operationalProperties.put("stock.recovery.initial-backoff", "PT0S");
            case "maximum" -> operationalProperties.put("stock.recovery.max-backoff", "PT30S");
            default -> operationalProperties.put("stock.recovery.monitor-interval", "PT61S");
        }
        assertThrows(org.springframework.beans.factory.BeanCreationException.class, this::openContext);
    }

    @Test void leaseExpiredWorkerCannotUndoAbortOrClearNewOwnersClaim() throws Exception {
        var id = ready(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var barrier = new java.util.concurrent.atomic.AtomicBoolean();
        var puts = new java.util.concurrent.atomic.AtomicInteger();
        when(inventory.get(any())).thenThrow(missing());
        when(inventory.reserve(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (puts.incrementAndGet() == 1) {
                entered.countDown();
                assertTrue(release.await(15, TimeUnit.SECONDS));
                assertTrue(barrier.get(), "Late PUT must meet the durable remote abort barrier");
            }
            throw new StockIntegrationFailure(409, StockFailureCategory.INVALID_UPSTREAM_RESPONSE);
        });
        when(inventory.abort(any())).thenAnswer(call -> {
            barrier.set(true);
            return new InventoryReservationClient.AbortProof(id, null);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> recovery(50).runOnce());
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertTrue(stock().due(Instant.now(), 50).isEmpty());
                sql("UPDATE $schema.stock_work SET claimed_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE order_id = '" + id + "'");
                recovery(50).runOnce();
                assertEquals(StockWorkPhase.CLOSED, stock().get(id).phase());
                assertEquals(OrderStatus.CANCELLED, response(id).status());
            } finally { release.countDown(); }
            stale.get(10, TimeUnit.SECONDS);
        }
        assertTrue(barrier.get());
        assertEquals(2, puts.get());
        assertEquals(StockWorkPhase.CLOSED, stock().get(id).phase());
        assertTrue(stock().get(id).abortRequested());
        assertEquals(OrderStatus.CANCELLED, response(id).status());
    }

    private OrderPaymentTransaction payment() { return context.getBean(OrderPaymentTransaction.class); }
    private UUID payable() { var id = ready(); observe(id, StockReservationStatus.ACTIVE); return id; }

    @Test void paymentHandoffCommitsSnapshotPublicStateAndClaimInvalidationTogether() {
        var id = payable(); var before = response(id);
        var claim = stock().claim(id, Instant.now(), Duration.ofSeconds(60)).orElseThrow();
        var accepted = payment().request(customer, id);
        assertFalse(accepted.terminal()); assertEquals("PROCESSING", accepted.order().paymentStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, accepted.order().status());
        assertEquals(before.items(), accepted.order().items()); assertEquals(before.totalAmount(), accepted.order().totalAmount());
        assertEquals(before.currency(), accepted.order().currency()); assertEquals(accepted.order(), response(id));
        var work = stock().get(id); assertEquals(StockWorkPhase.PAYMENT_PENDING, work.phase());
        assertFalse(work.abortRequested()); assertNull(work.nextAttemptAt());
        assertTrue(stock().finish(claim, true, Instant.now().plusSeconds(120), Duration.ofMinutes(15)).isEmpty());
        assertTrue(stock().claim(id, Instant.now().plusSeconds(120), Duration.ofSeconds(60)).isEmpty());
        assertTrue(stock().due(Instant.now().plusSeconds(120), 50).isEmpty());
        var snapshot = context.getBean(PaymentWorkRepository.class).findById(id).orElseThrow();
        assertEquals(PaymentWorkPhase.CAPTURE_PENDING, snapshot.getPhase()); assertEquals(before.totalAmount(), snapshot.getAmount());
        assertEquals("EUR", snapshot.getCurrency()); assertNotNull(snapshot.getNextAttemptAt());
        assertEquals(1, count("payment_work")); verifyNoInteractions(inventory);
        assertEquals(CheckoutIntentStatus.COMPLETED, checkout().get(work.checkoutId()).status());
        assertEquals(accepted.order(), checkout().persistOrder(work.checkoutId(), order()));
    }

    @Test void concurrentPaymentRequestsCreateOneWorkAndReplayWithoutChangingVersionsOrDates() throws Exception {
        var id = payable();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1); var futures = new ArrayList<Future<OrderPaymentTransaction.Result>>();
            for (int n = 0; n < 8; n++) futures.add(executor.submit(() -> { gate.await(); return payment().request(customer, id); }));
            gate.countDown(); var first = futures.getFirst().get(15, TimeUnit.SECONDS);
            for (var future : futures) assertEquals(first, future.get(15, TimeUnit.SECONDS));
            var version = jdbc.queryForObject("SELECT version FROM " + schema + ".orders WHERE id=?", Long.class, id);
            assertEquals(first, payment().request(customer, id));
            assertEquals(version, jdbc.queryForObject("SELECT version FROM " + schema + ".orders WHERE id=?", Long.class, id));
        }
        assertEquals(1, count("payment_work")); verifyNoInteractions(inventory);
    }

    @Test void restartRetainsHandoffAndHistoricalOrdersRemainUnenrolled() {
        var id = payable(); var accepted = payment().request(customer, id);
        context.close(); openContext();
        assertEquals(accepted, payment().request(customer, id)); assertEquals(accepted.order(), response(id));
        assertEquals(StockWorkPhase.PAYMENT_PENDING, flow().resume(id).phase()); verifyNoInteractions(inventory);
        assertNull(response(existingOrder).paymentStatus());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> payment().request(customer, existingOrder)).getStatusCode().value());
        assertEquals(1, count("payment_work"));
    }

    @Test void missingAndOtherOwnerNeverCreateWork() {
        var id = payable();
        for (var target : List.of(id, UUID.randomUUID()))
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> payment().request(UUID.randomUUID(), target)).getStatusCode().value());
        assertEquals(0, count("payment_work")); assertNull(response(id).paymentStatus());
        assertEquals(StockWorkPhase.RESERVED, stock().get(id).phase());
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING_STOCK", "CONFIRMED", "CANCELLED", "SHIPPED", "DELIVERED", "COMPLETED"})
    void incompatibleOrderStatusDoesNotTransferOrCreatePayment(String status) {
        var id = payable(); sql("UPDATE $schema.orders SET status='" + status + "' WHERE id='" + id + "'");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> payment().request(customer, id)).getStatusCode().value());
        assertEquals(0, count("payment_work")); assertNull(response(id).paymentStatus());
        assertEquals(StockWorkPhase.RESERVED, stock().get(id).phase());
    }

    @ParameterizedTest @ValueSource(strings = {"WAITING_CART", "RESERVE_PENDING", "ABORT_PENDING", "REVIEW_REQUIRED", "CLOSED"})
    void incompatibleStockPhaseDoesNotStartPayment(String phase) {
        var id = persist().id();
        if (!phase.equals("WAITING_CART")) {
            checkout().completed(stock().get(id).checkoutId()); stock().cartCompleted(id);
            if (phase.equals("ABORT_PENDING") || phase.equals("CLOSED")) {
                stock().requestAbort(id, StockWorkReason.RESERVATION_REJECTED);
                if (phase.equals("CLOSED")) stock().aborted(id, id, null);
            } else if (phase.equals("REVIEW_REQUIRED")) stock().review(id, StockFailureCategory.INVALID_UPSTREAM_RESPONSE);
        }
        sql("UPDATE $schema.orders SET status='PENDING_PAYMENT' WHERE id='" + id + "'");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> payment().request(customer, id)).getStatusCode().value());
        assertEquals(StockWorkPhase.valueOf(phase), stock().get(id).phase()); assertEquals(0, count("payment_work"));
    }

    @ParameterizedTest @ValueSource(strings = {"total_amount=0", "currency='USD'"})
    void invalidPersistedFinancialTermsAreNotAccepted(String change) {
        var id = payable(); sql("UPDATE $schema.orders SET " + change + " WHERE id='" + id + "'");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> payment().request(customer, id)).getStatusCode().value());
        assertEquals(StockWorkPhase.RESERVED, stock().get(id).phase()); assertEquals(0, count("payment_work"));
    }

    @Test void paymentInsertFailureRollsBackPublicStateHandoffAndClaimInvalidation() {
        var id = payable(); var before = response(id);
        var claim = stock().claim(id, Instant.now(), Duration.ofSeconds(60)).orElseThrow(); var work = stock().get(id);
        sql("ALTER TABLE $schema.payment_work ADD CONSTRAINT reject_payment CHECK (amount <> 25)");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> payment().request(customer, id));
        assertEquals(before, response(id)); assertEquals(work, stock().get(id)); assertEquals(0, count("payment_work"));
        var metadata = jdbc.queryForObject("SELECT claim_token FROM " + schema + ".stock_work WHERE order_id=?", UUID.class, id);
        assertEquals(claim.token(), metadata);
        sql("ALTER TABLE $schema.payment_work DROP CONSTRAINT reject_payment");
        assertEquals("PROCESSING", payment().request(customer, id).order().paymentStatus());
    }

    @Test void oldStockTransitionsAfterHandoffCannotReviewAbortCancelOrReschedule() {
        var id = payable(); payment().request(customer, id); var accepted = response(id); var before = stock().get(id);
        stock().observe(id, null, Instant.now()); stock().observe(id, observation(id, StockReservationStatus.CONFIRMED), Instant.now());
        stock().observe(id, observation(id, StockReservationStatus.EXPIRED), Instant.now());
        stock().requestAbort(id, StockWorkReason.RESERVATION_EXPIRED); stock().review(id, StockFailureCategory.INVALID_UPSTREAM_RESPONSE);
        stock().aborted(id, id, null); stock().retry(id, StockWorkPhase.PAYMENT_PENDING, StockFailureCategory.UPSTREAM_UNAVAILABLE);
        stock().recordFailure(id, StockWorkPhase.PAYMENT_PENDING, StockFailureCategory.PERSISTENCE_FAILURE, Instant.now());
        assertEquals(before, flow().resume(id)); assertEquals(accepted, response(id)); verifyNoInteractions(inventory);
    }

    @Test void stockTransferFailureRollsBackPaymentInsertAndPublicStatus() {
        var id = payable(); var before = response(id); var work = stock().get(id);
        sql("ALTER TABLE $schema.stock_work ADD CONSTRAINT reject_transfer CHECK (phase <> 'PAYMENT_PENDING')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> payment().request(customer, id));
        assertEquals(before, response(id)); assertEquals(work, stock().get(id)); assertEquals(0, count("payment_work"));
        sql("ALTER TABLE $schema.stock_work DROP CONSTRAINT reject_transfer");
        assertEquals("PROCESSING", payment().request(customer, id).order().paymentStatus());
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "EXPIRED", "CONFIRMED", "invalid", "unavailable"})
    void workerReturningAfterLeaseAndHandoffCannotAbortOrOverwrite(String outcome) throws Exception {
        var id = payable(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(inventory.get(any())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS));
            if (outcome.equals("invalid")) throw new StockIntegrationFailure(502, StockFailureCategory.INVALID_UPSTREAM_RESPONSE);
            if (outcome.equals("unavailable")) throw new StockIntegrationFailure(503, StockFailureCategory.UPSTREAM_UNAVAILABLE);
            return observation(id, StockReservationStatus.valueOf(outcome));
        });
        var originalContext = context;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> recovery(50).runOnce());
            OrderResponse accepted;
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                sql("UPDATE $schema.stock_work SET claimed_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE order_id='" + id + "'");
                openContext(); // A second Order context/EntityManagerFactory takes over while the first worker is paused.
                accepted = payment().request(customer, id).order();
                recovery(50).runOnce();
            } finally { release.countDown(); }
            stale.get(15, TimeUnit.SECONDS);
            assertEquals(accepted, response(id)); assertEquals(StockWorkPhase.PAYMENT_PENDING, stock().get(id).phase());
        } finally { originalContext.close(); }
        verify(inventory).get(any()); verify(inventory, never()).abort(any()); verify(inventory, never()).reserve(any());
    }

    @Test void racingAbortAndPaymentHasOneWinnerAndNeverCompetingCoordinators() throws Exception {
        var id = payable(); var gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = executor.submit(() -> { gate.await();
                try { return payment().request(customer, id); }
                catch (ResponseStatusException failure) { assertEquals(409, failure.getStatusCode().value()); return null; }
            });
            var abort = executor.submit(() -> { gate.await(); return stock().requestAbort(id, StockWorkReason.RESERVATION_EXPIRED); });
            gate.countDown(); var accepted = request.get(15, TimeUnit.SECONDS); var finalWork = abort.get(15, TimeUnit.SECONDS);
            if (accepted == null) {
                assertEquals(StockWorkPhase.ABORT_PENDING, finalWork.phase()); assertEquals(0, count("payment_work"));
            } else {
                assertEquals(StockWorkPhase.PAYMENT_PENDING, finalWork.phase()); assertFalse(finalWork.abortRequested());
                assertEquals(1, count("payment_work"));
            }
        }
    }

    @Test void terminalPaymentWorkReplaysCurrentOrderWithoutReopening() {
        var id = payable(); payment().request(customer, id);
        sql("UPDATE $schema.payment_work SET phase='DONE',next_attempt_at=NULL,payment_status='CAPTURED',"
                + "payment_created_at=created_at,payment_updated_at=created_at,captured_at=created_at WHERE order_id='" + id + "'");
        sql("UPDATE $schema.orders SET status='CONFIRMED',payment_status='CAPTURED' WHERE id='" + id + "'");
        var replay = payment().request(customer, id);
        assertTrue(replay.terminal()); assertEquals(OrderStatus.CONFIRMED, replay.order().status());
        assertEquals("CAPTURED", replay.order().paymentStatus()); assertEquals(1, count("payment_work"));
    }

    @Test void paymentHttpAndGetUsePersistedOwnerAndSnapshotIncludingAdminIsolation() throws Exception {
        var id = payable();
        try (var web = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
            web.setParent(context); web.setServletContext(new org.springframework.mock.web.MockServletContext());
            web.register(PaymentMvcConfig.class, br.com.hadryan.shosphere.order.config.SecurityConfig.class,
                    br.com.hadryan.shosphere.order.controller.OrderPaymentController.class,
                    br.com.hadryan.shosphere.order.controller.OrderController.class,
                    br.com.hadryan.shosphere.order.exception.ApiExceptionHandler.class);
            web.refresh();
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
                    .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
            var path = "/api/v1/orders/" + id;
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path + "/payment")
                            .header("Authorization", "Bearer " + customer))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.paymentStatus").value("PROCESSING"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + customer))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.paymentStatus").value("PROCESSING"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].productName").value("Financial snapshot"));
            for (var target : List.of(id, UUID.randomUUID()))
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/orders/" + target + "/payment")
                                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                        .jwt(token -> token.subject(UUID.randomUUID().toString()))
                                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            verifyNoInteractions(web.getBean(br.com.hadryan.shosphere.order.service.CreateOrderUseCase.class), inventory);
            assertEquals(1, count("payment_work"));
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class PaymentMvcConfig {
        @Bean tools.jackson.databind.json.JsonMapper jsonMapper() { return tools.jackson.databind.json.JsonMapper.builder().build(); }
        @Bean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() {
            return token -> org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token).header("alg", "RS256").subject(token).build();
        }
        @Bean br.com.hadryan.shosphere.order.service.CreateOrderUseCase createOrder() {
            return mock(br.com.hadryan.shosphere.order.service.CreateOrderUseCase.class);
        }
    }

    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @Import({CheckoutIntentTransaction.class, StockWorkTransaction.class, StockReservationFlow.class, CreateOrderTransaction.class, GetOrderByIdUseCaseImpl.class, OrderPaymentTransaction.class})
    static class Config {
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }
}
