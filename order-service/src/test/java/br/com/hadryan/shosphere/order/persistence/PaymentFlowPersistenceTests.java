package br.com.hadryan.shosphere.order.persistence;

import br.com.hadryan.shosphere.order.client.*;
import br.com.hadryan.shosphere.order.model.*;
import br.com.hadryan.shosphere.order.model.Order;
import br.com.hadryan.shosphere.order.model.enums.*;
import br.com.hadryan.shosphere.order.repository.*;
import br.com.hadryan.shosphere.order.service.impl.*;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class PaymentFlowPersistenceTests {
    private final UUID id = UUID.randomUUID(), customer = UUID.randomUUID(), checkout = UUID.randomUUID(), product = UUID.randomUUID();
    private final Instant created = Instant.parse("2026-11-01T12:00:00Z"), expires = created.plusSeconds(900);
    private final InventoryReservationClient inventory = mock(InventoryReservationClient.class);
    private final PaymentClient money = mock(PaymentClient.class);
    private final AtomicReference<StockReservationStatus> remoteStock = new AtomicReference<>(StockReservationStatus.ACTIVE);
    private final AtomicReference<OrderPaymentStatus> remoteMoney = new AtomicReference<>();
    private final AtomicReference<OrderPaymentStatus> captureResult = new AtomicReference<>(OrderPaymentStatus.CAPTURED), refundResult = new AtomicReference<>(OrderPaymentStatus.REFUNDED);
    private final AtomicInteger captures = new AtomicInteger(), refunds = new AtomicInteger();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private AnnotationConfigApplicationContext context;
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach void setup() {
        var pool = new HikariConfig(); pool.setJdbcUrl(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"));
        pool.setUsername(System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"));
        pool.setPassword(System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        pool.setMaximumPoolSize(4); pool.setMinimumIdle(1); source = new HikariDataSource(pool);
        jdbc = new JdbcTemplate(source); schema = "payment_flow_test_" + UUID.randomUUID().toString().replace("-", "");
        migrate("7").migrate();
        jdbc.update("INSERT INTO " + schema + ".orders(id,customer_id,status,subtotal,total_amount,payment_status) VALUES (?,?,'PENDING_PAYMENT',25,25,'PROCESSING')", id, customer);
        jdbc.update("INSERT INTO " + schema + ".order_items(id,order_id,product_id,product_name,quantity,unit_price,line_total) VALUES (?,?,?,'Financial snapshot',2,12.50,25)", UUID.randomUUID(), id, product);
        jdbc.update("INSERT INTO " + schema + ".checkout_intents(checkout_id,customer_id,idempotency_key,status,shipping_recipient,shipping_address_line,shipping_city,shipping_postal_code,shipping_country_code,cart_id,order_id,created_at,updated_at) "
                + "VALUES (?,?,?,'COMPLETED','Customer','Street','Lisbon','1000-001','PT',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", checkout, customer, UUID.randomUUID().toString(), UUID.randomUUID(), id);
        jdbc.update("INSERT INTO " + schema + ".stock_work(order_id,checkout_id,phase,reservation_status,reservation_created_at,reservation_expires_at,reservation_updated_at,created_at,updated_at) "
                + "VALUES (?,?,'PAYMENT_PENDING','ACTIVE',?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, checkout, java.sql.Timestamp.from(created), java.sql.Timestamp.from(expires), java.sql.Timestamp.from(created));
        jdbc.update("INSERT INTO " + schema + ".payment_work(order_id,phase,amount,currency,next_attempt_at,created_at,updated_at) VALUES (?,'CAPTURE_PENDING',25,'EUR',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id);
        assertEquals(2, migrate(null).migrate().migrationsExecuted);
        open(); installRemotes();
    }
    private Flyway migrate(String target) {
        var config = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration");
        if (target != null) config.target(target); return config.load();
    }
    private void open() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("payment-flow", Map.of("checkout.snapshot.enabled", "true")));
        context.registerBean(DataSource.class, () -> source, bean -> bean.setDestroyMethodName(""));
        context.registerBean(PaymentClient.class, () -> money); context.registerBean(InventoryReservationClient.class, () -> inventory);
        context.registerBean("entityManagerFactory", LocalContainerEntityManagerFactoryBean.class, () -> {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(source);
            factory.setPackagesToScan(Order.class.getPackageName()); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.default_schema", schema, "hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")); return factory;
        }); context.register(Config.class); context.refresh();
    }
    private void restart() { context.close(); open(); }
    @AfterEach void cleanup() {
        if (context != null) context.close();
        if (schema == null || !schema.matches("payment_flow_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test schema");
        try { jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        finally { if (source != null) source.close(); }
    }
    private void outside(String event) { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); events.add(event); }
    private void installRemotes() {
        doAnswer(call -> { outside("stock-get"); if (remoteStock.get() == null) throw stockFailure(404); return reservation(remoteStock.get()); }).when(inventory).get(any());
        doAnswer(call -> {
            outside("stock-confirm");
            if (!remoteStock.compareAndSet(StockReservationStatus.ACTIVE, StockReservationStatus.CONFIRMED) && remoteStock.get() != StockReservationStatus.CONFIRMED) throw stockFailure(409);
            return reservation(StockReservationStatus.CONFIRMED);
        }).when(inventory).confirm(any());
        doAnswer(call -> {
            outside("stock-abort"); if (remoteStock.get() == StockReservationStatus.CONFIRMED) throw stockFailure(409);
            remoteStock.compareAndSet(StockReservationStatus.ACTIVE, StockReservationStatus.RELEASED);
            return new InventoryReservationClient.AbortProof(id, remoteStock.get() == null ? null : reservation(remoteStock.get()));
        }).when(inventory).abort(any());
        doAnswer(call -> { outside("money-get"); if (remoteMoney.get() == null) throw moneyFailure(404); return proof(remoteMoney.get()); }).when(money).get(any());
        doAnswer(call -> performCapture()).when(money).capture(any());
        doAnswer(call -> performCancel()).when(money).cancel(any());
    }
    private PaymentObservation performCapture() {
        outside("money-capture");
        synchronized (remoteMoney) {
            var status = remoteMoney.get();
            if (status == OrderPaymentStatus.CANCELLED || status == OrderPaymentStatus.REFUNDED || status == OrderPaymentStatus.REFUND_PENDING) throw moneyFailure(409);
            if (status == null || status == OrderPaymentStatus.PROCESSING) {
                remoteMoney.set(captureResult.get()); if (captureResult.get() == OrderPaymentStatus.CAPTURED) captures.incrementAndGet();
            }
            return proof(remoteMoney.get());
        }
    }
    private PaymentObservation performCancel() {
        outside("money-cancel"); assertTrue(tx().get(id).stockSafe());
        assertNotEquals(StockReservationStatus.CONFIRMED, remoteStock.get());
        synchronized (remoteMoney) {
            var status = remoteMoney.get();
            if (status == null || status == OrderPaymentStatus.PROCESSING) remoteMoney.set(OrderPaymentStatus.CANCELLED);
            else if (status == OrderPaymentStatus.CAPTURED || status == OrderPaymentStatus.REFUND_PENDING) {
                remoteMoney.set(refundResult.get()); if (refundResult.get() == OrderPaymentStatus.REFUNDED) refunds.incrementAndGet();
            }
            return proof(remoteMoney.get());
        }
    }
    private PaymentIntegrationFailure moneyFailure(int code) { return new PaymentIntegrationFailure(code, code >= 500 ? PaymentFailureCategory.UPSTREAM_UNAVAILABLE : code == 401 || code == 403 ? PaymentFailureCategory.AUTHENTICATION_CONFIGURATION : PaymentFailureCategory.INVALID_UPSTREAM_RESPONSE); }
    private StockIntegrationFailure stockFailure(int code) { return new StockIntegrationFailure(code, code >= 500 ? StockFailureCategory.UPSTREAM_UNAVAILABLE : StockFailureCategory.INVALID_UPSTREAM_RESPONSE); }
    private PaymentObservation proof(OrderPaymentStatus status) {
        boolean captured = status == OrderPaymentStatus.CAPTURED || status == OrderPaymentStatus.REFUND_PENDING || status == OrderPaymentStatus.REFUNDED;
        return new PaymentObservation(id, status, new BigDecimal("25.00"), "EUR", created, status == OrderPaymentStatus.PROCESSING ? created : status == OrderPaymentStatus.REFUNDED ? created.plusSeconds(3) : created.plusSeconds(1),
                captured ? created.plusSeconds(1) : null, status == OrderPaymentStatus.REFUNDED ? created.plusSeconds(3) : null);
    }
    private StockReservationObservation reservation(StockReservationStatus status) {
        return new StockReservationObservation(id, status, List.of(new StockWorkSnapshot.Item(product, 2)), created, expires,
                status == StockReservationStatus.ACTIVE ? created : created.plusSeconds(2));
    }
    private PaymentWorkTransaction tx() { return context.getBean(PaymentWorkTransaction.class); }
    private PaymentWorkSnapshot step() { return context.getBean(PaymentFlow.class).resume(id); }
    private br.com.hadryan.shosphere.order.mapper.response.OrderResponse order() { return context.getBean(br.com.hadryan.shosphere.order.service.GetOrderByIdUseCase.class).execute(customer, id); }
    private void sql(String statement) { jdbc.execute(statement.replace("$schema", schema)); }
    private void capture() { assertEquals(PaymentWorkPhase.CONFIRM_PENDING, step().phase()); }
    private void resolving() { tx().resolution(id, tx().get(id).phase()); }
    private void stockSafe() { remoteStock.set(StockReservationStatus.EXPIRED); assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase()); }

    @Test void upgradePreservesAcceptedIntentionWithoutInventingProofsOrEffects() {
        var work = tx().get(id); assertEquals(PaymentWorkPhase.CAPTURE_PENDING, work.phase());
        assertNull(work.payment()); assertFalse(work.stockSafe()); assertEquals("PROCESSING", order().paymentStatus());
        assertEquals(0, migrate(null).migrate().migrationsExecuted); assertTrue(migrate(null).validateWithResult().validationSuccessful);
        verifyNoInteractions(money, inventory);
    }
    @Test void confirmsOnlyAfterBothProofsAndSealsStockFromOldWorker() {
        var original = order(); capture(); assertEquals("CAPTURED", order().paymentStatus()); assertEquals(OrderStatus.PENDING_PAYMENT, order().status());
        verify(inventory, never()).confirm(any());
        assertEquals(PaymentWorkPhase.DONE, step().phase()); assertEquals(OrderStatus.CONFIRMED, order().status());
        assertEquals("CAPTURED", order().paymentStatus()); assertEquals(original.items(), order().items()); assertEquals(original.totalAmount(), order().totalAmount());
        assertEquals(StockWorkPhase.SETTLED, tx().get(id).stock().phase());
        var stock = context.getBean(StockWorkTransaction.class);
        assertEquals(StockWorkPhase.SETTLED, stock.requestAbort(id, StockWorkReason.RESERVATION_EXPIRED).phase());
        assertEquals(StockWorkPhase.SETTLED, stock.review(id, StockFailureCategory.UNEXPECTED_CONFIRMED).phase());
        var count = events.size(); step(); assertEquals(count, events.size()); assertEquals(1, captures.get()); assertEquals(0, refunds.get());
        assertTrue(context.getBean(OrderPaymentTransaction.class).request(customer, id).terminal());
    }
    @Test void businessDeclineWaitsForSafeStockBeforeCancellingOrder() {
        captureResult.set(OrderPaymentStatus.DECLINED); assertEquals(PaymentWorkPhase.RESOLUTION_PENDING, step().phase());
        assertEquals("PROCESSING", order().paymentStatus()); assertEquals(OrderStatus.PENDING_PAYMENT, order().status());
        assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase()); assertEquals(PaymentWorkPhase.CLOSED, step().phase());
        assertEquals(OrderStatus.CANCELLED, order().status()); assertEquals("DECLINED", order().paymentStatus());
        assertEquals(0, captures.get()); assertEquals(0, refunds.get()); verify(inventory, never()).confirm(any());
        assertTrue(events.indexOf("stock-abort") < events.indexOf("money-cancel"));
    }
    @Test void terminalStockBeforeCaptureUsesCancellationBarrierWithoutCharging() {
        remoteStock.set(StockReservationStatus.RELEASED); assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase());
        verifyNoInteractions(money); assertEquals(PaymentWorkPhase.CLOSED, step().phase());
        assertEquals("CANCELLED", order().paymentStatus()); assertEquals(0, captures.get());
    }
    @Test void expiryBetweenCaptureAndConfirmRequiresFullRefund() {
        capture(); stockSafe(); assertEquals("REFUND_PENDING", order().paymentStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, order().status()); assertEquals(PaymentWorkPhase.CLOSED, step().phase());
        assertEquals("REFUNDED", order().paymentStatus()); assertEquals(OrderStatus.CANCELLED, order().status());
        assertEquals(1, captures.get()); assertEquals(1, refunds.get()); verify(inventory, never()).confirm(any());
    }
    @Test void confirmConflictResolvesStockBeforeRefundInsteadOfUsingItsErrorText() {
        capture(); doAnswer(call -> { outside("stock-confirm"); remoteStock.set(StockReservationStatus.EXPIRED); throw stockFailure(409); }).when(inventory).confirm(any());
        assertEquals(PaymentWorkPhase.RESOLUTION_PENDING, step().phase()); assertEquals(0, refunds.get());
        assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase()); assertEquals(PaymentWorkPhase.CLOSED, step().phase()); assertEquals(1, refunds.get());
    }
    @Test void lostCaptureResponseIsReconciledAfterRestartWithoutChargingAgain() {
        doAnswer(call -> { performCapture(); throw moneyFailure(503); }).when(money).capture(any());
        assertEquals(PaymentWorkPhase.CAPTURE_PENDING, step().phase()); assertEquals("PROCESSING", order().paymentStatus());
        restart(); capture(); assertEquals(PaymentWorkPhase.DONE, step().phase()); assertEquals(1, captures.get()); verify(money, times(1)).capture(any());
    }
    @Test void lostConfirmResponseIsReadBackWithoutRefundOrSecondConsumption() {
        capture(); doAnswer(call -> { outside("stock-confirm"); remoteStock.set(StockReservationStatus.CONFIRMED); throw stockFailure(503); }).when(inventory).confirm(any());
        assertEquals(PaymentWorkPhase.CONFIRM_PENDING, step().phase()); restart();
        assertEquals(PaymentWorkPhase.DONE, step().phase()); verify(inventory, times(1)).confirm(any()); verify(money, never()).cancel(any());
    }
    @Test void lostRefundResponseKeepsDecisionAndClosesOnReplayAfterRestart() {
        capture(); stockSafe(); doAnswer(call -> { performCancel(); throw moneyFailure(503); }).when(money).cancel(any());
        assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase()); assertEquals(OrderStatus.PENDING_PAYMENT, order().status());
        restart(); doAnswer(call -> performCancel()).when(money).cancel(any());
        assertEquals(PaymentWorkPhase.CLOSED, step().phase()); assertEquals(1, refunds.get()); assertEquals("REFUNDED", order().paymentStatus());
    }
    @Test void pendingCaptureProgressesByIdempotentPutAfterGetAndNeverConfirmsEarly() {
        captureResult.set(OrderPaymentStatus.PROCESSING); assertEquals(PaymentWorkPhase.CAPTURE_PENDING, step().phase());
        verify(inventory, never()).confirm(any()); captureResult.set(OrderPaymentStatus.CAPTURED); restart(); capture();
        assertEquals(PaymentWorkPhase.DONE, step().phase()); assertEquals(1, captures.get());
    }
    @Test void unknownCaptureAfterLostResponseIsRefundedWhenStockHasExpired() {
        doAnswer(call -> { performCapture(); throw moneyFailure(503); }).when(money).capture(any());
        assertEquals(PaymentWorkPhase.CAPTURE_PENDING, step().phase()); remoteStock.set(StockReservationStatus.EXPIRED);
        assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase()); restart();
        assertEquals(PaymentWorkPhase.CLOSED, step().phase()); assertEquals("REFUNDED", order().paymentStatus());
        assertEquals(1, captures.get()); assertEquals(1, refunds.get());
    }
    @Test void persistedSafeStockProofAllowsRefundAfterRestartWithoutDependingOnAnotherStockRead() {
        capture(); stockSafe(); restart();
        doThrow(stockFailure(503)).when(inventory).get(any());
        assertEquals(PaymentWorkPhase.CLOSED, step().phase()); assertEquals(1, refunds.get());
    }
    @Test void lostAbortResponseIsResolvedByTerminalReadBeforePaymentCancellation() {
        capture(); resolving();
        doAnswer(call -> { outside("stock-abort"); remoteStock.set(StockReservationStatus.RELEASED); throw stockFailure(503); }).when(inventory).abort(any());
        assertEquals(PaymentWorkPhase.RESOLUTION_PENDING, step().phase()); verify(money, never()).cancel(any()); restart();
        assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase()); assertEquals(PaymentWorkPhase.CLOSED, step().phase());
        assertEquals(1, refunds.get());
    }
    @Test void pendingRefundSurvivesRestartAndNeverDeclaresCancelledBeforeProof() {
        capture(); stockSafe(); refundResult.set(OrderPaymentStatus.REFUND_PENDING);
        assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase()); assertEquals(OrderStatus.PENDING_PAYMENT, order().status());
        assertEquals("REFUND_PENDING", order().paymentStatus()); restart(); refundResult.set(OrderPaymentStatus.REFUNDED);
        assertEquals(PaymentWorkPhase.CLOSED, step().phase()); assertEquals(1, refunds.get());
    }
    @ParameterizedTest @ValueSource(ints = {401, 403, 500, 503})
    void technicalPaymentFailuresNeverBecomeBusinessDecline(int code) {
        doThrow(moneyFailure(code)).when(money).get(any()); var result = step();
        assertEquals(PaymentWorkPhase.CAPTURE_PENDING, result.phase()); assertEquals("PROCESSING", order().paymentStatus());
        assertEquals(code < 500 ? PaymentFailureCategory.AUTHENTICATION_CONFIGURATION : PaymentFailureCategory.UPSTREAM_UNAVAILABLE, result.lastFailureCategory());
        verify(money, never()).capture(any()); verify(inventory, never()).confirm(any());
    }
    @ParameterizedTest @ValueSource(strings = {"amount", "currency", "id", "created", "capture-date", "missing-capture"})
    void incompatibleMonetaryProofRequiresReviewWithoutConfirmOrRefund(String variant) {
        capture(); var good = proof(OrderPaymentStatus.CAPTURED);
        var bad = new PaymentObservation(variant.equals("id") ? UUID.randomUUID() : id, good.status(),
                variant.equals("amount") ? new BigDecimal("26.00") : good.amount(), variant.equals("currency") ? "USD" : "EUR",
                variant.equals("created") ? good.createdAt().minusSeconds(1) : good.createdAt(), good.updatedAt(),
                variant.equals("missing-capture") ? null : variant.equals("capture-date") ? good.capturedAt().minusNanos(1) : good.capturedAt(), null);
        doReturn(bad).when(money).get(any()); assertEquals(PaymentWorkPhase.REVIEW_REQUIRED, step().phase());
        assertEquals(OrderStatus.PENDING_PAYMENT, order().status()); assertEquals("REVIEW_REQUIRED", order().paymentStatus());
        verify(inventory, never()).confirm(any()); verify(money, never()).cancel(any()); var calls = events.size(); step(); assertEquals(calls, events.size());
    }
    @ParameterizedTest @ValueSource(strings = {"id", "items", "expiry", "created"})
    void incompatibleStockProofNeverAuthorizesMoney(String variant) {
        var bad = new StockReservationObservation(variant.equals("id") ? UUID.randomUUID() : id, StockReservationStatus.ACTIVE,
                List.of(new StockWorkSnapshot.Item(variant.equals("items") ? UUID.randomUUID() : product, 2)),
                variant.equals("created") ? created.minusSeconds(1) : created, variant.equals("expiry") ? expires.plusSeconds(1) : expires, created);
        doReturn(bad).when(inventory).get(any()); assertEquals(PaymentWorkPhase.REVIEW_REQUIRED, step().phase()); verifyNoInteractions(money);
    }
    @Test void inventory404AloneDoesNotCloseUntilDurableAbortAndPaymentBarrier() {
        remoteStock.set(null); assertEquals(PaymentWorkPhase.RESOLUTION_PENDING, step().phase()); assertFalse(tx().get(id).stockSafe());
        assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase()); assertTrue(tx().get(id).stockAbsent());
        assertEquals(PaymentWorkPhase.CLOSED, step().phase()); assertEquals(StockWorkPhase.CLOSED, tx().get(id).stock().phase()); assertEquals(0, captures.get());
    }
    @Test void abortConflictObservesWinningConfirmationAndNeverRefunds() {
        capture(); resolving(); doAnswer(call -> { outside("stock-abort"); remoteStock.set(StockReservationStatus.CONFIRMED); throw stockFailure(409); }).when(inventory).abort(any());
        assertEquals(PaymentWorkPhase.DONE, step().phase()); assertEquals(OrderStatus.CONFIRMED, order().status()); verify(money, never()).cancel(any());
    }
    @Test void abortConflictWithNoCompatibleTerminalProofRequiresReview() {
        capture(); resolving(); doThrow(stockFailure(409)).when(inventory).abort(any());
        assertEquals(PaymentWorkPhase.REVIEW_REQUIRED, step().phase()); verify(money, never()).cancel(any());
    }
    @Test void confirmedStockWithDeclinedPaymentRequiresReviewInsteadOfInventingCapture() {
        captureResult.set(OrderPaymentStatus.DECLINED); step(); remoteStock.set(StockReservationStatus.CONFIRMED);
        assertEquals(PaymentWorkPhase.REVIEW_REQUIRED, step().phase()); assertEquals(0, captures.get()); verify(money, never()).cancel(any());
    }
    @Test void confirmedStockWithMissingPaymentDoesNotBecomeSuccessfulOrRefunded() {
        remoteStock.set(StockReservationStatus.CONFIRMED); assertEquals(PaymentWorkPhase.RESOLUTION_PENDING, step().phase());
        assertEquals(PaymentWorkPhase.REVIEW_REQUIRED, step().phase()); verify(money, never()).capture(any()); verify(money, never()).cancel(any());
    }
    @Test void lateConfirmedContradictingSafeStockStopsCompensationForReview() {
        capture(); stockSafe();
        assertEquals(PaymentWorkPhase.REVIEW_REQUIRED, tx().inventory(id, PaymentWorkPhase.CONFIRM_PENDING, reservation(StockReservationStatus.CONFIRMED)).phase());
        step(); verify(money, never()).cancel(any()); assertEquals(OrderStatus.PENDING_PAYMENT, order().status());
    }
    @Test void lateConfirmedAfterFinancialClosureMarksReviewWithoutReopeningOrMutatingMoney() {
        capture(); stockSafe(); assertEquals(PaymentWorkPhase.CLOSED, step().phase());
        assertEquals(OrderStatus.CANCELLED, order().status()); assertEquals(1, refunds.get());
        assertEquals(PaymentWorkPhase.REVIEW_REQUIRED, tx().inventory(id, PaymentWorkPhase.CONFIRM_PENDING,
                reservation(StockReservationStatus.CONFIRMED)).phase());
        assertEquals(OrderStatus.CANCELLED, order().status()); assertEquals("REVIEW_REQUIRED", order().paymentStatus());
        var calls = events.size(); step(); assertEquals(calls, events.size()); assertEquals(1, refunds.get());
    }
    @Test void completionFailureRollsBackBothPublicAndStockStatesAfterRemoteSuccess() {
        capture(); sql("ALTER TABLE $schema.payment_work ADD CONSTRAINT reject_done CHECK (phase <> 'DONE')");
        assertEquals(PaymentWorkPhase.CONFIRM_PENDING, step().phase()); assertEquals(OrderStatus.PENDING_PAYMENT, order().status());
        assertEquals(StockWorkPhase.PAYMENT_PENDING, tx().get(id).stock().phase()); assertEquals(StockReservationStatus.CONFIRMED, remoteStock.get());
        sql("ALTER TABLE $schema.payment_work DROP CONSTRAINT reject_done"); restart(); assertEquals(PaymentWorkPhase.DONE, step().phase()); assertEquals(1, captures.get());
    }
    @Test void cancellationFailureRetainsRefundProofRemotelyAndPendingStateLocally() {
        capture(); stockSafe(); sql("ALTER TABLE $schema.payment_work ADD CONSTRAINT reject_closed CHECK (phase <> 'CLOSED')");
        assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase()); assertEquals(OrderStatus.PENDING_PAYMENT, order().status());
        sql("ALTER TABLE $schema.payment_work DROP CONSTRAINT reject_closed"); restart(); assertEquals(PaymentWorkPhase.CLOSED, step().phase()); assertEquals(1, refunds.get());
    }
    @Test void staleConfirmCannotConsumeAfterSafeStockAndRefundHaveWon() throws Exception {
        capture(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            outside("stock-confirm"); entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS));
            if (!remoteStock.compareAndSet(StockReservationStatus.ACTIVE, StockReservationStatus.CONFIRMED)) throw stockFailure(409);
            return reservation(StockReservationStatus.CONFIRMED);
        }).when(inventory).confirm(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(this::step);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS)); resolving();
                assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase()); assertEquals(PaymentWorkPhase.CLOSED, step().phase());
            } finally { release.countDown(); }
            assertEquals(PaymentWorkPhase.CLOSED, stale.get(15, TimeUnit.SECONDS).phase());
        }
        assertEquals(OrderStatus.CANCELLED, order().status()); assertEquals(1, refunds.get()); assertEquals(StockReservationStatus.RELEASED, remoteStock.get());
    }
    @Test void confirmationThatWonBeforeAbortCompletesEvenWhenItsResponseIsPaused() throws Exception {
        capture(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            outside("stock-confirm"); remoteStock.set(StockReservationStatus.CONFIRMED); entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS)); return reservation(StockReservationStatus.CONFIRMED);
        }).when(inventory).confirm(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(this::step);
            try { assertTrue(entered.await(10, TimeUnit.SECONDS)); resolving(); assertEquals(PaymentWorkPhase.DONE, step().phase()); }
            finally { release.countDown(); }
            assertEquals(PaymentWorkPhase.DONE, stale.get(15, TimeUnit.SECONDS).phase());
        }
        assertEquals(OrderStatus.CONFIRMED, order().status()); verify(money, never()).cancel(any());
    }
    @Test void delayedCaptureCannotPassPaymentCancellationBarrier() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)); return performCapture(); }).when(money).capture(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(this::step);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS)); resolving();
                assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, step().phase()); assertEquals(PaymentWorkPhase.CLOSED, step().phase());
            } finally { release.countDown(); }
            assertEquals(PaymentWorkPhase.CLOSED, stale.get(15, TimeUnit.SECONDS).phase());
        }
        assertEquals(0, captures.get()); assertEquals("CANCELLED", order().paymentStatus());
    }
    @Test void concurrentResumesProduceOneCaptureAndOneConfirmationWithoutRegressingTerminalState() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1); var futures = new ArrayList<Future<PaymentWorkSnapshot>>();
            for (int n = 0; n < 8; n++) futures.add(executor.submit(() -> { gate.await(); return step(); }));
            gate.countDown(); for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        for (int n = 0; n < 4 && !tx().get(id).phase().terminal(); n++) step();
        assertEquals(PaymentWorkPhase.DONE, tx().get(id).phase()); assertEquals(1, captures.get()); assertEquals(OrderStatus.CONFIRMED, order().status());
    }
    @Test void databaseRejectsIncompleteFinancialProofsAndUnsafeClosure() {
        for (var change : List.of("phase='DONE',next_attempt_at=NULL", "phase='CLOSED',next_attempt_at=NULL",
                "phase='CANCEL_PAYMENT_PENDING'", "payment_status='CAPTURED'", "stock_absent=true",
                "payment_status='PROCESSING',payment_created_at=created_at", "last_failure_category='sensitive'")) {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> sql("UPDATE $schema.payment_work SET " + change));
        }
        assertEquals(PaymentWorkPhase.CAPTURE_PENDING, tx().get(id).phase());
    }

    private PaymentRecoveryJob recovery() { return new PaymentRecoveryJob(tx(), context.getBean(PaymentFlow.class), 50,
            java.time.Duration.ofSeconds(60), java.time.Duration.ofMinutes(15), java.time.Duration.ofSeconds(60)); }
    private void makePaymentDue() { jdbc.update("UPDATE " + schema + ".payment_work SET next_attempt_at=? WHERE order_id=?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id); }
    private void expirePaymentLease() { jdbc.update("UPDATE " + schema + ".payment_work SET claimed_until=? WHERE order_id=?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id); }
    private PaymentWorkClaim claimPayment() { makePaymentDue(); return tx().claim(id, Instant.now(), java.time.Duration.ofSeconds(60)).orElseThrow(); }
    private void pass() { makePaymentDue(); recovery().runOnce(); }

    @Test void scheduledPassesProgressWithoutPublicReplayOrUserJwtAndCloseBothWorks() {
        pass(); assertEquals(PaymentWorkPhase.CONFIRM_PENDING, tx().get(id).phase()); assertEquals("CAPTURED", order().paymentStatus());
        pass(); assertEquals(PaymentWorkPhase.DONE, tx().get(id).phase()); assertEquals(StockWorkPhase.SETTLED, tx().get(id).stock().phase());
        assertTrue(tx().due(Instant.now().plusSeconds(10000), 50).isEmpty()); assertEquals(1, captures.get());
    }
    @ParameterizedTest @ValueSource(strings = {"capture", "confirm", "abort", "cancel"})
    void workerRecoversLostRemoteResultsAfterRestartWithoutUserToken(String boundary) {
        switch (boundary) {
            case "capture" -> doAnswer(call -> { performCapture(); throw moneyFailure(503); }).when(money).capture(any());
            case "confirm" -> { capture(); doAnswer(call -> { remoteStock.set(StockReservationStatus.CONFIRMED); throw stockFailure(503); }).when(inventory).confirm(any()); }
            case "abort" -> { capture(); resolving(); doAnswer(call -> { remoteStock.set(StockReservationStatus.RELEASED); throw stockFailure(503); }).when(inventory).abort(any()); }
            case "cancel" -> { capture(); stockSafe(); doAnswer(call -> { performCancel(); throw moneyFailure(503); }).when(money).cancel(any()); }
        }
        pass(); assertEquals(OrderStatus.PENDING_PAYMENT, order().status()); restart(); installRemotes();
        for (int n = 0; n < 4 && !tx().get(id).phase().terminal(); n++) pass();
        assertTrue(tx().get(id).phase().terminal()); assertEquals(1, captures.get());
        assertEquals(boundary.equals("abort") || boundary.equals("cancel") ? 1 : 0, refunds.get());
        assertTrue(tx().due(Instant.now().plusSeconds(10000), 50).isEmpty());
    }
    @Test void claimsAreUniqueSurviveRestartAndObsoleteOwnerCannotMutateOrReleaseNewClaim() throws Exception {
        makePaymentDue();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1); var futures = new ArrayList<Future<Optional<PaymentWorkClaim>>>();
            for (int n = 0; n < 8; n++) futures.add(executor.submit(() -> { gate.await(); return tx().claim(id, Instant.now(), java.time.Duration.ofSeconds(60)); }));
            gate.countDown(); var claims = new ArrayList<PaymentWorkClaim>();
            for (var future : futures) future.get(10, TimeUnit.SECONDS).ifPresent(claims::add);
            assertEquals(1, claims.size()); var first = claims.getFirst(); restart();
            assertTrue(tx().due(Instant.now(), 50).isEmpty()); expirePaymentLease(); var next = claimPayment();
            assertNotEquals(first.token(), next.token());
            assertThrows(PaymentClaimLostException.class, () -> tx().payment(id, PaymentWorkPhase.CAPTURE_PENDING, proof(OrderPaymentStatus.CAPTURED), first.token()));
            assertThrows(PaymentClaimLostException.class, () -> tx().resolution(id, PaymentWorkPhase.CAPTURE_PENDING, first.token()));
            assertThrows(PaymentClaimLostException.class, () -> tx().failure(id, PaymentWorkPhase.CAPTURE_PENDING, PaymentFailureCategory.PERSISTENCE_FAILURE, first.token()));
            assertThrows(PaymentClaimLostException.class, () -> tx().inventory(id, PaymentWorkPhase.CAPTURE_PENDING, reservation(StockReservationStatus.EXPIRED), first.token()));
            assertThrows(PaymentClaimLostException.class, () -> tx().aborted(id, id, null, first.token()));
            assertTrue(tx().finish(first, true, Instant.now(), java.time.Duration.ofMinutes(15)).isEmpty());
            context.getBean(PaymentFlow.class).resume(first); verifyNoInteractions(money, inventory);
            assertEquals(next.token(), jdbc.queryForObject("SELECT claim_token FROM " + schema + ".payment_work", UUID.class));
        }
    }
    @Test void expiredLeaseStopsBeforeHttpEvenWithoutReplacementOwner() {
        var claim = claimPayment(); expirePaymentLease(); context.getBean(PaymentFlow.class).resume(claim); verifyNoInteractions(money, inventory);
        assertThrows(PaymentClaimLostException.class, () -> tx().get(id, claim.token()));
        assertTrue(tx().finish(claim, false, Instant.now(), java.time.Duration.ofMinutes(15)).isPresent());
    }
    @ParameterizedTest @ValueSource(strings = {"stock_work", "orders", "payment_work"})
    void claimSkipsLockedRowsWithoutInvertingStockOrderPaymentLockOrder(String table) throws Exception {
        makePaymentDue();
        try (var connection = source.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) { statement.executeQuery("SELECT 1 FROM " + schema + "." + table + " WHERE "
                    + (table.equals("orders") ? "id" : "order_id") + "='" + id + "' FOR UPDATE").close(); }
            assertTrue(executor.submit(() -> tx().claim(id, Instant.now(), java.time.Duration.ofSeconds(60))).get(3, TimeUnit.SECONDS).isEmpty());
            if (table.equals("payment_work")) assertTrue(executor.submit(() -> tx().due(Instant.now(), 50)).get(3, TimeUnit.SECONDS).isEmpty());
            connection.rollback();
        }
        assertTrue(tx().claim(id, Instant.now(), java.time.Duration.ofSeconds(60)).isPresent());
    }
    @Test void backoffPersistsAcrossRestartCapsAndNeverDoubleCountsBookkeepingFallback() {
        int attempts = 0;
        for (int seconds : new int[]{60,120,240,480,900,900}) {
            var claim = claimPayment(); var now = Instant.now();
            tx().failure(id, PaymentWorkPhase.CAPTURE_PENDING, PaymentFailureCategory.UPSTREAM_UNAVAILABLE, claim.token());
            var result = tx().finish(claim, true, now, java.time.Duration.ofMinutes(15)).orElseThrow();
            assertEquals(++attempts, result.attempts());
            var next = jdbc.queryForObject("SELECT next_attempt_at FROM " + schema + ".payment_work", java.sql.Timestamp.class).toInstant();
            assertEquals(now.plusSeconds(seconds).getEpochSecond(), next.getEpochSecond());
            if (attempts == 3) restart();
        }
    }
    @Test void nextAttemptAnticipatesReservationDeadlineWithoutDeclaringItExpiredLocally() {
        var deadline = Instant.now().plusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("UPDATE " + schema + ".stock_work SET reservation_expires_at=?", java.sql.Timestamp.from(deadline));
        var claim = claimPayment(); tx().finish(claim, false, Instant.now(), java.time.Duration.ofMinutes(15));
        var next = jdbc.queryForObject("SELECT next_attempt_at FROM " + schema + ".payment_work", java.sql.Timestamp.class).toInstant();
        assertEquals(deadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS), next);
        assertEquals(OrderStatus.PENDING_PAYMENT, order().status()); assertFalse(tx().get(id).stockSafe());
    }
    @Test void warningAndRetryAreDurableAndAgeOrAttemptsNeverAbandonWork() {
        jdbc.update("UPDATE " + schema + ".payment_work SET created_at=?,attempt_count=1000", java.sql.Timestamp.from(Instant.now().minusSeconds(3600)));
        doThrow(moneyFailure(503)).when(money).get(any()); pass();
        assertEquals(PaymentWorkPhase.CAPTURE_PENDING, tx().get(id).phase());
        assertEquals(1001, jdbc.queryForObject("SELECT attempt_count FROM " + schema + ".payment_work", Long.class));
        var warning = jdbc.queryForObject("SELECT last_warning_at FROM " + schema + ".payment_work", java.sql.Timestamp.class);
        assertNotNull(warning); restart(); installRemotes(); pass();
        assertEquals(warning, jdbc.queryForObject("SELECT last_warning_at FROM " + schema + ".payment_work", java.sql.Timestamp.class));
        pass(); assertEquals(PaymentWorkPhase.DONE, tx().get(id).phase()); assertEquals(1, captures.get());
    }
    @Test void reviewOnlyReadsAndUsesLongDiagnosticIntervalWithoutReopeningMoney() {
        tx().failure(id, PaymentWorkPhase.CAPTURE_PENDING, PaymentFailureCategory.INCONSISTENT_PROOF);
        clearInvocations(money, inventory); pass();
        verify(money).get(any()); verify(inventory).get(any()); verify(money, never()).capture(any()); verify(money, never()).cancel(any());
        verify(inventory, never()).confirm(any()); verify(inventory, never()).abort(any());
        assertEquals(PaymentWorkPhase.REVIEW_REQUIRED, tx().get(id).phase()); assertEquals("REVIEW_REQUIRED", order().paymentStatus());
        assertTrue(tx().due(Instant.now().plusSeconds(899), 50).isEmpty());
    }
    @Test void failedFinishRollsBackClaimReleaseScheduleCounterAndWarningAndCanBeRetried() {
        var claim = claimPayment();
        sql("ALTER TABLE $schema.payment_work ADD CONSTRAINT reject_finish CHECK (claim_token IS NOT NULL)");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> tx().finish(claim, true, Instant.now(), java.time.Duration.ofMinutes(15)));
        assertEquals(claim.token(), jdbc.queryForObject("SELECT claim_token FROM " + schema + ".payment_work", UUID.class));
        assertEquals(0, jdbc.queryForObject("SELECT attempt_count FROM " + schema + ".payment_work", Long.class));
        sql("ALTER TABLE $schema.payment_work DROP CONSTRAINT reject_finish"); restart();
        assertEquals(1, tx().finish(claim, true, Instant.now(), java.time.Duration.ofMinutes(15)).orElseThrow().attempts());
    }
    @Test void staleGetAfterLeaseLossCannotDispatchCaptureOrEraseAnotherOwnersProof() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var gets = new AtomicInteger();
        doAnswer(call -> { outside("money-get"); if (gets.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)); } throw moneyFailure(404); }).when(money).get(any());
        var first = claimPayment();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> context.getBean(PaymentFlow.class).resume(first));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS)); expirePaymentLease(); var second = claimPayment();
                assertEquals(PaymentWorkPhase.CONFIRM_PENDING, context.getBean(PaymentFlow.class).resume(second).phase());
                tx().finish(second, false, Instant.now(), java.time.Duration.ofMinutes(15));
            } finally { release.countDown(); }
            assertEquals(PaymentWorkPhase.CONFIRM_PENDING, stale.get(15, TimeUnit.SECONDS).phase());
        }
        assertTrue(tx().finish(first, true, Instant.now(), java.time.Duration.ofMinutes(15)).isEmpty()); assertEquals(1, captures.get());
    }
    @Test void staleConfirmAfterLeaseLossCannotReverseSafeRefundOrReleaseCurrentClaim() throws Exception {
        capture(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            outside("stock-confirm"); entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS));
            if (!remoteStock.compareAndSet(StockReservationStatus.ACTIVE, StockReservationStatus.CONFIRMED)) throw stockFailure(409);
            return reservation(StockReservationStatus.CONFIRMED);
        }).when(inventory).confirm(any());
        var first = claimPayment();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> context.getBean(PaymentFlow.class).resume(first));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS)); expirePaymentLease(); var second = claimPayment();
                tx().resolution(id, PaymentWorkPhase.CONFIRM_PENDING, second.token());
                assertEquals(PaymentWorkPhase.CANCEL_PAYMENT_PENDING, context.getBean(PaymentFlow.class).resume(second).phase());
                assertEquals(PaymentWorkPhase.CLOSED, context.getBean(PaymentFlow.class).resume(second).phase());
                assertEquals(second.token(), jdbc.queryForObject("SELECT claim_token FROM " + schema + ".payment_work", UUID.class));
            } finally { release.countDown(); }
            assertEquals(PaymentWorkPhase.CLOSED, stale.get(15, TimeUnit.SECONDS).phase());
        }
        assertTrue(tx().finish(first, true, Instant.now(), java.time.Duration.ofMinutes(15)).isEmpty());
        assertEquals(1, refunds.get()); assertEquals(OrderStatus.CANCELLED, order().status());
    }
    @Test void confirmationWinningBeforeLeaseLossIsReconciledByNewOwnerWithoutRefund() throws Exception {
        capture(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { outside("stock-confirm"); remoteStock.set(StockReservationStatus.CONFIRMED); entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS)); return reservation(StockReservationStatus.CONFIRMED); }).when(inventory).confirm(any());
        var first = claimPayment();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> context.getBean(PaymentFlow.class).resume(first));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS)); expirePaymentLease(); var second = claimPayment();
                assertEquals(PaymentWorkPhase.DONE, context.getBean(PaymentFlow.class).resume(second).phase());
                tx().finish(second, false, Instant.now(), java.time.Duration.ofMinutes(15));
            } finally { release.countDown(); }
            assertEquals(PaymentWorkPhase.DONE, stale.get(15, TimeUnit.SECONDS).phase());
        }
        assertTrue(tx().finish(first, true, Instant.now(), java.time.Duration.ofMinutes(15)).isEmpty());
        assertEquals(OrderStatus.CONFIRMED, order().status()); assertEquals(0, refunds.get());
    }
    @Test void refundWinningBeforeLeaseLossIsReconciledOnceAndOldOwnerCannotClearNewClaim() throws Exception {
        capture(); stockSafe(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var cancels = new AtomicInteger();
        doAnswer(call -> { var result = performCancel(); if (cancels.incrementAndGet() == 1) { entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS)); } return result; }).when(money).cancel(any());
        var first = claimPayment(); PaymentWorkClaim second;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> context.getBean(PaymentFlow.class).resume(first));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS)); expirePaymentLease(); second = claimPayment();
                assertEquals(PaymentWorkPhase.CLOSED, context.getBean(PaymentFlow.class).resume(second).phase());
            } finally { release.countDown(); }
            assertEquals(PaymentWorkPhase.CLOSED, stale.get(15, TimeUnit.SECONDS).phase());
        }
        assertTrue(tx().finish(first, true, Instant.now(), java.time.Duration.ofMinutes(15)).isEmpty());
        assertEquals(second.token(), jdbc.queryForObject("SELECT claim_token FROM " + schema + ".payment_work", UUID.class));
        tx().finish(second, false, Instant.now(), java.time.Duration.ofMinutes(15));
        assertEquals(1, refunds.get()); assertEquals("REFUNDED", order().paymentStatus());
    }
    @Test void claimAndAttemptInvariantsAreEnforcedByV9WithoutChangingExistingProofs() {
        assertThrows(org.springframework.dao.DataAccessException.class, () -> sql("UPDATE $schema.payment_work SET claim_token='" + UUID.randomUUID() + "'"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> sql("UPDATE $schema.payment_work SET claimed_until=CURRENT_TIMESTAMP"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> sql("UPDATE $schema.payment_work SET attempt_count=-1"));
        assertNull(tx().get(id).payment()); assertEquals("PROCESSING", order().paymentStatus());
        assertEquals("9", migrate(null).info().current().getVersion().getVersion());
    }
    @Test void overdueWarningIsThrottledDurablyAndCanRepeatAfterItsInterval() {
        jdbc.update("UPDATE " + schema + ".payment_work SET created_at=?", java.sql.Timestamp.from(Instant.now().minusSeconds(3600)));
        var now = Instant.now(); var first = claimPayment();
        assertTrue(tx().finish(first, false, now, java.time.Duration.ofMinutes(15)).orElseThrow().warnOverdue());
        restart(); var second = claimPayment();
        assertFalse(tx().finish(second, false, now.plusSeconds(899), java.time.Duration.ofMinutes(15)).orElseThrow().warnOverdue());
        var third = claimPayment();
        assertTrue(tx().finish(third, false, now.plusSeconds(901), java.time.Duration.ofMinutes(15)).orElseThrow().warnOverdue());
        assertEquals(PaymentWorkPhase.CAPTURE_PENDING, tx().get(id).phase()); verifyNoInteractions(money, inventory);
    }

    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @Import({PaymentWorkTransaction.class, PaymentFlow.class, StockWorkTransaction.class, OrderPaymentTransaction.class, GetOrderByIdUseCaseImpl.class})
    static class Config {
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }
}
