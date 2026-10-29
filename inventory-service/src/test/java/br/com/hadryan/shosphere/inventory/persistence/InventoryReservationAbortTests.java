package br.com.hadryan.shosphere.inventory.persistence;

import br.com.hadryan.shosphere.inventory.InventoryServiceApplication;
import br.com.hadryan.shosphere.inventory.client.CatalogClient;
import br.com.hadryan.shosphere.inventory.exception.ReservationStateConflictException;
import br.com.hadryan.shosphere.inventory.exception.StockReservationConflictException;
import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.model.InventoryStock;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.repository.*;
import br.com.hadryan.shosphere.inventory.service.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class InventoryReservationAbortTests {
    private DriverManagerDataSource dataSource;
    private String schema;
    private ApplicationContextRunner runner;
    private ApplicationContext context;
    private JdbcTemplate jdbc;
    private final String client = "shopsphere-order";
    private final UUID order = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();

    @BeforeEach void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        jdbc = new JdbcTemplate(dataSource);
        schema = "inventory_abort_test_" + UUID.randomUUID().toString().replace("-", "");
        runner = new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(InventoryServiceApplication.class)
                .withBean(InventoryStartupPersistenceTests.InventoryTestComponentFilter.class,
                        InventoryStartupPersistenceTests.InventoryTestComponentFilter::new)
                .withBean(DataSource.class, () -> dataSource).withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
                .withBean(CatalogClient.class, () -> mock(CatalogClient.class))
                .withPropertyValues("spring.flyway.schemas=" + schema, "spring.flyway.default-schema=" + schema,
                        "spring.jpa.properties.hibernate.default_schema=" + schema, "services.catalog.url=http://catalog.invalid");
    }

    @AfterEach void tearDown() {
        if (!schema.matches("inventory_abort_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test schema");
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private void run(Executable operation) {
        runner.run(ctx -> { assertNull(ctx.getStartupFailure()); context = ctx.getSourceApplicationContext(); operation.execute(); });
    }
    private ReserveStockUseCase reserve() { return context.getBean(ReserveStockUseCase.class); }
    private AbortReservationUseCase abort() { return context.getBean(AbortReservationUseCase.class); }
    private TransitionReservationUseCase transition() { return context.getBean(TransitionReservationUseCase.class); }
    private InventoryStockRepository stocks() { return context.getBean(InventoryStockRepository.class); }
    private InventoryReservationCoordinationRepository coordinations() { return context.getBean(InventoryReservationCoordinationRepository.class); }
    private ReserveStockRequest request(UUID id, int quantity) { return new ReserveStockRequest(List.of(new ReserveStockRequest.Item(id, quantity))); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + schema + "." + table, Long.class); }
    private void sql(String sql) { jdbc.execute(sql.replace("$schema", schema)); }
    private void status(int expected, Executable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }
    private int attempt(Executable operation) throws Throwable {
        try { operation.execute(); return 200; }
        catch (ResponseStatusException failure) { return failure.getStatusCode().value(); }
        catch (ReservationStateConflictException | StockReservationConflictException failure) { return 409; }
    }
    private List<Integer> race(Executable first, Executable second) throws Exception {
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                try { return attempt(action); }
                catch (Throwable failure) { throw new IllegalStateException(failure); }
            })).toList();
            return List.of(futures.getFirst().get(15, TimeUnit.SECONDS), futures.getLast().get(15, TimeUnit.SECONDS));
        }
    }

    @Test void abortWithoutReservationOrStockPersistsBarrierAndReplaysWithoutChanges() {
        run(() -> {
            var result = abort().execute(client, order, request(product, Integer.MAX_VALUE));
            assertEquals(order, result.orderId()); assertNull(result.reservation());
            var before = coordinations().findById(order).orElseThrow();
            assertNotNull(before.getAbortedAt());
            assertEquals(result, abort().execute(client, order, request(product, Integer.MAX_VALUE)));
            var after = coordinations().findById(order).orElseThrow();
            assertEquals(before.getAbortedAt(), after.getAbortedAt()); assertEquals(before.getVersion(), after.getVersion());
            status(409, () -> reserve().execute(client, order, request(product, Integer.MAX_VALUE)));
            status(404, () -> context.getBean(GetReservationUseCase.class).execute(client, order));
            status(404, () -> transition().release(client, order));
            assertEquals(0, count("inventory_reservations")); assertEquals(0, count("inventory_stock"));
            stocks().saveAndFlush(new InventoryStock(product, Integer.MAX_VALUE));
            status(409, () -> reserve().execute(client, order, request(product, Integer.MAX_VALUE)));
            assertEquals(0, stocks().findById(product).orElseThrow().getReserved());
        });
    }

    @Test void abortActivePreservesOtherReservationsAndPhysicalBalanceAndOnlyReleasesOnce() {
        run(() -> {
            stocks().saveAndFlush(new InventoryStock(product, 10));
            var other = UUID.randomUUID();
            reserve().execute(client, order, request(product, 3)); reserve().execute(client, other, request(product, 2));
            var result = abort().execute(client, order, request(product, 3));
            assertEquals(InventoryReservationStatus.RELEASED, result.reservation().status());
            var stock = stocks().findById(product).orElseThrow();
            var guard = coordinations().findById(order).orElseThrow();
            assertEquals(10, stock.getOnHand()); assertEquals(2, stock.getReserved());
            assertEquals(result, abort().execute(client, order, request(product, 3)));
            assertEquals(stock.getVersion(), stocks().findById(product).orElseThrow().getVersion());
            assertEquals(guard.getVersion(), coordinations().findById(order).orElseThrow().getVersion());
            status(409, () -> reserve().execute(client, order, request(product, 3)));
            assertEquals(InventoryReservationStatus.ACTIVE, context.getBean(GetReservationUseCase.class).execute(client, other).status());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"EXPIRED", "RELEASED", "CONFIRMED"})
    void abortRespectsTerminalStatesAndNeverReversesConsumption(String state) {
        run(() -> {
            stocks().saveAndFlush(new InventoryStock(product, 2));
            reserve().execute(client, order, request(product, 1));
            if (state.equals("EXPIRED")) sql("UPDATE $schema.inventory_reservations SET created_at=now()-interval '2 hours', expires_at=now()-interval '1 hour' WHERE order_id='" + order + "'");
            if (state.equals("RELEASED")) transition().release(client, order);
            if (state.equals("CONFIRMED")) {
                var original = transition().confirm(client, order);
                var version = coordinations().findById(order).orElseThrow().getVersion();
                assertThrows(ReservationStateConflictException.class, () -> abort().execute(client, order, request(product, 1)));
                assertNull(coordinations().findById(order).orElseThrow().getAbortedAt());
                assertEquals(version, coordinations().findById(order).orElseThrow().getVersion());
                assertEquals(original, context.getBean(GetReservationUseCase.class).execute(client, order));
                assertEquals(1, stocks().findById(product).orElseThrow().getOnHand());
            } else {
                var result = abort().execute(client, order, request(product, 1));
                assertEquals(InventoryReservationStatus.valueOf(state), result.reservation().status());
                assertFalse(context.getBean(ExpireReservationUseCase.class).execute(order));
                assertEquals(result, abort().execute(client, order, request(product, 1)));
                assertEquals(2, stocks().findById(product).orElseThrow().getOnHand());
            }
            assertEquals(0, stocks().findById(product).orElseThrow().getReserved());
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void mismatchedIntentAndOwnerDoNotChangeReservationOrBarrier(boolean existing) {
        run(() -> {
            if (existing) { stocks().saveAndFlush(new InventoryStock(product, 5)); reserve().execute(client, order, request(product, 2)); }
            else abort().execute(client, order, request(product, 2));
            var before = coordinations().findById(order).orElseThrow();
            status(404, () -> abort().execute("another-service", order, request(product, 3)));
            status(404, () -> reserve().execute("another-service", order, request(product, 2)));
            status(409, () -> abort().execute(client, order, request(product, 3)));
            status(409, () -> reserve().execute(client, order, request(product, 3)));
            var after = coordinations().findById(order).orElseThrow();
            assertEquals(before.getAbortedAt(), after.getAbortedAt()); assertEquals(before.getVersion(), after.getVersion());
            if (existing) { assertEquals(2, stocks().findById(product).orElseThrow().getReserved()); assertNull(after.getAbortedAt()); }
        });
    }

    @Test void rejectedReservationRollsBackCoordinationAndCanThenBeAborted() {
        run(() -> {
            status(404, () -> reserve().execute(client, order, request(product, 1)));
            assertEquals(0, count("inventory_reservation_coordination"));
            stocks().saveAndFlush(new InventoryStock(product, 0));
            assertThrows(StockReservationConflictException.class, () -> reserve().execute(client, order, request(product, 1)));
            assertEquals(0, count("inventory_reservation_coordination"));
            assertNull(abort().execute(client, order, request(product, 1)).reservation());
            assertEquals(1, count("inventory_reservation_coordination"));
        });
    }

    @Test void failureSavingBarrierRollsBackReleaseAndAllStockItems() {
        run(() -> {
            var second = UUID.randomUUID();
            stocks().saveAllAndFlush(List.of(new InventoryStock(product, 10), new InventoryStock(second, 10)));
            var intent = new ReserveStockRequest(List.of(new ReserveStockRequest.Item(product, 2), new ReserveStockRequest.Item(second, 3)));
            var original = reserve().execute(client, order, intent).reservation();
            sql("ALTER TABLE $schema.inventory_reservation_coordination ADD CONSTRAINT test_reject_abort CHECK (aborted_at IS NULL)");
            assertThrows(DataIntegrityViolationException.class, () -> abort().execute(client, order, intent));
            assertEquals(original, context.getBean(GetReservationUseCase.class).execute(client, order));
            assertNull(coordinations().findById(order).orElseThrow().getAbortedAt());
            assertEquals(2, stocks().findById(product).orElseThrow().getReserved());
            assertEquals(3, stocks().findById(second).orElseThrow().getReserved());
            sql("ALTER TABLE $schema.inventory_reservation_coordination DROP CONSTRAINT test_reject_abort");
            assertEquals(InventoryReservationStatus.RELEASED, abort().execute(client, order, intent).reservation().status());
            for (var stock : stocks().findAll()) { assertEquals(0, stock.getReserved()); assertEquals(10, stock.getOnHand()); }
        });
    }

    @Test void upgradeV4PreservesExistingReservationAndAddsNoInventedIntent() {
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("4").load().migrate();
        sql("INSERT INTO $schema.inventory_stock(product_id,on_hand,reserved,version) VALUES ('" + product + "',10,2,7)");
        sql("INSERT INTO $schema.inventory_reservations(order_id,service_client_id,status,created_at,expires_at) VALUES ('" + order + "','" + client + "','ACTIVE',now(),now()+interval '15 minutes')");
        sql("INSERT INTO $schema.inventory_reservation_items VALUES ('" + order + "','" + product + "',2)");
        run(() -> {
            assertEquals(0, count("inventory_reservation_coordination"));
            assertEquals(7L, stocks().findById(product).orElseThrow().getVersion());
            assertEquals(2, stocks().findById(product).orElseThrow().getReserved());
            status(409, () -> abort().execute(client, order, request(product, 3)));
            assertEquals(0, count("inventory_reservation_coordination"));
            status(404, () -> abort().execute("other", order, request(product, 2)));
            assertEquals(0, count("inventory_reservation_coordination"));
            assertEquals(InventoryReservationStatus.RELEASED, abort().execute(client, order, request(product, 2)).reservation().status());
            assertEquals(1, count("inventory_reservation_coordination"));
        });
    }

    @Test void restartPreservesAbortedIdentitySnapshotAndVersion() {
        run(() -> abort().execute(client, order, request(product, 7)));
        run(() -> {
            assertEquals(order, abort().execute(client, order, request(product, 7)).orderId());
            status(409, () -> reserve().execute(client, order, request(product, 7)));
            status(409, () -> abort().execute(client, order, request(product, 8)));
            assertEquals(7, jdbc.queryForObject("SELECT quantity FROM " + schema + ".inventory_reservation_coordination_items WHERE order_id=?", Integer.class, order));
            assertEquals(0, count("inventory_reservations"));
        });
    }

    @Test void concurrentCreationAndAbortAlwaysConvergeWithoutActiveStock() {
        run(() -> {
            stocks().saveAndFlush(new InventoryStock(product, 1));
            var result = race(() -> reserve().execute(client, order, request(product, 1)), () -> abort().execute(client, order, request(product, 1)));
            assertTrue(result.getFirst() == 200 || result.getFirst() == 409); assertEquals(200, result.getLast());
            assertNotNull(coordinations().findById(order).orElseThrow().getAbortedAt());
            assertEquals(0, stocks().findById(product).orElseThrow().getReserved());
            assertEquals(1, stocks().findById(product).orElseThrow().getOnHand());
            status(409, () -> reserve().execute(client, order, request(product, 1)));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"confirm", "release", "expire", "abort"})
    void concurrentAbortAndOtherTransitionsNeverDoubleReleaseOrConsume(String operation) {
        run(() -> {
            stocks().saveAndFlush(new InventoryStock(product, 1)); reserve().execute(client, order, request(product, 1));
            if (operation.equals("expire")) sql("UPDATE $schema.inventory_reservations SET created_at=now()-interval '2 hours', expires_at=now()-interval '1 hour' WHERE order_id='" + order + "'");
            var outcomes = race(() -> abort().execute(client, order, request(product, 1)), () -> {
                switch (operation) {
                    case "confirm" -> transition().confirm(client, order);
                    case "release" -> transition().release(client, order);
                    case "expire" -> context.getBean(ExpireReservationUseCase.class).execute(order);
                    default -> abort().execute(client, order, request(product, 1));
                }
            });
            var status = context.getBean(GetReservationUseCase.class).execute(client, order).status();
            var stock = stocks().findById(product).orElseThrow();
            assertEquals(0, stock.getReserved()); assertEquals(2L, stock.getVersion());
            assertEquals(status == InventoryReservationStatus.CONFIRMED ? 0 : 1, stock.getOnHand());
            if (status == InventoryReservationStatus.CONFIRMED) {
                assertEquals(409, outcomes.getFirst()); assertNull(coordinations().findById(order).orElseThrow().getAbortedAt());
            } else { assertEquals(200, outcomes.getFirst()); assertNotNull(coordinations().findById(order).orElseThrow().getAbortedAt()); }
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void inFlightPutWaitsForUncommittedAbsentAbortAndRespectsCommitOrRollback(boolean commit) {
        run(() -> {
            stocks().saveAndFlush(new InventoryStock(product, 1));
            try (var connection = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
                connection.setAutoCommit(false);
                try (var insert = connection.prepareStatement("INSERT INTO " + schema + ".inventory_reservation_coordination(order_id,service_client_id,created_at,aborted_at) VALUES (?,?,now(),now())")) {
                    insert.setObject(1, order); insert.setString(2, client); insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO " + schema + ".inventory_reservation_coordination_items VALUES (?,?,1)")) {
                    insert.setObject(1, order); insert.setObject(2, product); insert.executeUpdate();
                }
                var started = new CountDownLatch(1);
                var pending = executor.submit(() -> {
                    started.countDown();
                    try { return attempt(() -> reserve().execute(client, order, request(product, 1))); }
                    catch (Throwable failure) { throw new IllegalStateException(failure); }
                });
                try {
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> pending.get(200, TimeUnit.MILLISECONDS));
                } finally { if (commit) connection.commit(); else connection.rollback(); }
                assertEquals(commit ? 409 : 200, pending.get(10, TimeUnit.SECONDS));
            }
            assertEquals(commit ? 0 : 1, stocks().findById(product).orElseThrow().getReserved());
        });
    }

    @Test void hundredItemsAreAbortedAtomicallyAndReorderedReplayIsStable() {
        run(() -> {
            var products = java.util.stream.IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
            stocks().saveAllAndFlush(products.stream().map(id -> new InventoryStock(id, 1)).toList());
            var items = products.stream().map(id -> new ReserveStockRequest.Item(id, 1)).toList();
            var payload = new ReserveStockRequest(items);
            reserve().execute(client, order, payload);
            var result = abort().execute(client, order, payload);
            var reversed = new ArrayList<>(items); Collections.reverse(reversed);
            assertEquals(result, abort().execute(client, order, new ReserveStockRequest(reversed)));
            assertEquals(100, result.reservation().items().size());
            for (var stock : stocks().findAll()) { assertEquals(1, stock.getOnHand()); assertEquals(0, stock.getReserved()); }
        });
    }

    @Test void databaseConstraintsProtectBarrierAndItemsWithoutForeignKeyToStock() {
        run(() -> {
            abort().execute(client, order, request(product, 1));
            assertEquals(0, count("inventory_stock"));
            assertSql("INSERT INTO inventory_reservation_coordination_items VALUES ('" + order + "','" + product + "',1)", "23505");
            assertSql("INSERT INTO inventory_reservation_coordination_items VALUES ('" + order + "','" + UUID.randomUUID() + "',0)", "23514");
            assertSql("INSERT INTO inventory_reservation_coordination_items VALUES ('" + UUID.randomUUID() + "','" + product + "',1)", "23503");
            assertSql("UPDATE inventory_reservation_coordination SET service_client_id=' ' WHERE order_id='" + order + "'", "23514");
            assertSql("UPDATE inventory_reservation_coordination SET aborted_at=created_at-interval '1 second' WHERE order_id='" + order + "'", "23514");
            assertSql("UPDATE inventory_reservation_coordination SET version=-1 WHERE order_id='" + order + "'", "23514");
        });
    }

    @Test void invalidAbortPayloadsAreRejectedBeforeAnyCoordinationIsWritten() {
        run(() -> {
            var invalid = new ArrayList<ReserveStockRequest>();
            invalid.add(null); invalid.add(new ReserveStockRequest(null)); invalid.add(new ReserveStockRequest(List.of()));
            invalid.add(request(product, 0));
            invalid.add(new ReserveStockRequest(List.of(new ReserveStockRequest.Item(product, 1), new ReserveStockRequest.Item(product, 2))));
            invalid.add(new ReserveStockRequest(java.util.stream.IntStream.range(0, 101)
                    .mapToObj(i -> new ReserveStockRequest.Item(UUID.randomUUID(), 1)).toList()));
            for (var payload : invalid) status(400, () -> abort().execute(client, order, payload));
            assertEquals(0, count("inventory_reservation_coordination"));
            assertEquals(0, count("inventory_reservations"));
        });
    }

    @Test void staleVersionCannotOverwriteCommittedAbort() {
        run(() -> {
            stocks().saveAndFlush(new InventoryStock(product, 1)); reserve().execute(client, order, request(product, 1));
            var stale = coordinations().findById(order).orElseThrow();
            abort().execute(client, order, request(product, 1));
            assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, () -> coordinations().saveAndFlush(stale));
            assertNotNull(coordinations().findById(order).orElseThrow().getAbortedAt());
            assertEquals(0, stocks().findById(product).orElseThrow().getReserved());
        });
    }

    @Test void overlappingAbortsWithOppositePayloadOrderUseConsistentProductLocks() {
        run(() -> {
            var second = UUID.randomUUID(); var otherOrder = UUID.randomUUID();
            stocks().saveAllAndFlush(List.of(new InventoryStock(product, 2), new InventoryStock(second, 2)));
            var forward = new ReserveStockRequest(List.of(new ReserveStockRequest.Item(product, 1), new ReserveStockRequest.Item(second, 1)));
            var reverse = new ReserveStockRequest(List.of(new ReserveStockRequest.Item(second, 1), new ReserveStockRequest.Item(product, 1)));
            reserve().execute(client, order, forward); reserve().execute(client, otherOrder, reverse);
            assertEquals(List.of(200, 200), race(() -> abort().execute(client, order, forward), () -> abort().execute(client, otherOrder, reverse)));
            for (var stock : stocks().findAll()) { assertEquals(2, stock.getOnHand()); assertEquals(0, stock.getReserved()); }
        });
    }

    private void assertSql(String sql, String state) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            assertEquals(state, assertThrows(SQLException.class, () -> statement.executeUpdate(sql)).getSQLState());
        }
    }
}
