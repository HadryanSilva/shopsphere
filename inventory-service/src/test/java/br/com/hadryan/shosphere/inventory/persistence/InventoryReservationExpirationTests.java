package br.com.hadryan.shosphere.inventory.persistence;

import br.com.hadryan.shosphere.inventory.InventoryServiceApplication;
import br.com.hadryan.shosphere.inventory.client.CatalogClient;
import br.com.hadryan.shosphere.inventory.exception.ReservationStateConflictException;
import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.model.InventoryStock;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.service.ExpireReservationUseCase;
import br.com.hadryan.shosphere.inventory.service.ReservationExpirationJob;
import br.com.hadryan.shosphere.inventory.service.GetReservationUseCase;
import br.com.hadryan.shosphere.inventory.service.ReserveStockUseCase;
import br.com.hadryan.shosphere.inventory.service.TransitionReservationUseCase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class InventoryReservationExpirationTests {
    private DriverManagerDataSource dataSource;
    private String schema;
    private ApplicationContextRunner runner;
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final String client = "shopsphere-order";

    @BeforeEach void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "inventory_expiration_test_" + UUID.randomUUID().toString().replace("-", "");
        runner = new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(InventoryServiceApplication.class)
                .withBean(InventoryStartupPersistenceTests.InventoryTestComponentFilter.class,
                        InventoryStartupPersistenceTests.InventoryTestComponentFilter::new)
                .withBean(DataSource.class, () -> dataSource).withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
                .withBean(CatalogClient.class, () -> catalog)
                .withPropertyValues("spring.flyway.schemas=" + schema, "spring.flyway.default-schema=" + schema,
                        "spring.jpa.properties.hibernate.default_schema=" + schema, "services.catalog.url=http://catalog.invalid",
                        "inventory.reservations.expiration.batch-size=1");
    }

    @AfterEach void tearDown() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void recoversExpiredReservationsAcrossRestartAndPagesEqualDeadlinesWithoutChangingLiveOne() {
        var product = UUID.randomUUID(); var a = UUID.randomUUID(); var b = UUID.randomUUID(); var live = UUID.randomUUID();
        runner.run(context -> {
            context.getBean(InventoryStockRepository.class).saveAndFlush(new InventoryStock(product, 3));
            var reserve = context.getBean(ReserveStockUseCase.class);
            for (var order : List.of(a, b, live)) reserve.execute(client, order, request(product));
            sql("UPDATE inventory_reservations SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 hours', expires_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE order_id IN ('" + a + "','" + b + "')");
        });
        runner.run(context -> {
            var query = context.getBean(GetReservationUseCase.class);
            var beforeLive = query.execute(client, live);
            context.getBean(ReservationExpirationJob.class).run();
            for (var order : List.of(a, b)) assertEquals(InventoryReservationStatus.EXPIRED, query.execute(client, order).status());
            assertEquals(beforeLive, query.execute(client, live));
            var stocks = context.getBean(InventoryStockRepository.class);
            assertEquals(3, stocks.findById(product).orElseThrow().getOnHand());
            assertEquals(1, stocks.findById(product).orElseThrow().getReserved());
            var version = stocks.findById(product).orElseThrow().getVersion();
            var expired = query.execute(client, a);
            context.getBean(ReservationExpirationJob.class).run();
            assertEquals(expired, query.execute(client, a));
            assertEquals(version, stocks.findById(product).orElseThrow().getVersion());
            verifyNoInteractions(catalog);
        });
    }

    @Test void workerIgnoresMissingUnexpiredAndTerminalReservations() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var live = UUID.randomUUID(); var confirmed = UUID.randomUUID(); var released = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 3));
            var reserve = context.getBean(ReserveStockUseCase.class);
            for (var order : List.of(live, confirmed, released)) reserve.execute(client, order, request(product));
            var transition = context.getBean(TransitionReservationUseCase.class);
            transition.confirm(client, confirmed); transition.release(client, released);
            expireFixtures(List.of(confirmed, released));
            var expire = context.getBean(ExpireReservationUseCase.class);
            for (var order : List.of(live, confirmed, released, UUID.randomUUID())) assertFalse(expire.execute(order));
            assertEquals(2, stocks.findById(product).orElseThrow().getOnHand());
            assertEquals(1, stocks.findById(product).orElseThrow().getReserved());
        });
    }

    @Test void failureRollsBackWholeReservationWithoutStarvingLaterPagesAndIsRetriedNextSweep() {
        runner.run(context -> {
            var a = new UUID(0, 1); var b = new UUID(0, 2); var c = new UUID(0, 3);
            var bad = UUID.randomUUID(); var good = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAllAndFlush(List.of(new InventoryStock(a, 1), new InventoryStock(b, 1), new InventoryStock(c, 1)));
            var reserve = context.getBean(ReserveStockUseCase.class);
            reserve.execute(client, bad, new ReserveStockRequest(List.of(new ReserveStockRequest.Item(a, 1), new ReserveStockRequest.Item(b, 1))));
            reserve.execute(client, good, request(c));
            expireFixtures(List.of(bad, good));
            sql("UPDATE inventory_reservations SET created_at=CURRENT_TIMESTAMP-INTERVAL '3 hours', expires_at=CURRENT_TIMESTAMP-INTERVAL '2 hours' WHERE order_id='" + bad + "'");
            // Corruption fixture: the second item fails after the first was changed in memory.
            sql("UPDATE inventory_stock SET reserved=0 WHERE product_id='" + b + "'");
            var before = context.getBean(GetReservationUseCase.class).execute(client, bad);
            var job = context.getBean(ReservationExpirationJob.class);
            job.run();
            assertEquals(before, context.getBean(GetReservationUseCase.class).execute(client, bad));
            assertEquals(1, stocks.findById(a).orElseThrow().getReserved());
            assertEquals(1L, stocks.findById(a).orElseThrow().getVersion());
            assertEquals(InventoryReservationStatus.EXPIRED, context.getBean(GetReservationUseCase.class).execute(client, good).status());
            assertEquals(0, stocks.findById(c).orElseThrow().getReserved());
            sql("UPDATE inventory_stock SET reserved=1 WHERE product_id='" + b + "'");
            job.run();
            assertEquals(InventoryReservationStatus.EXPIRED, context.getBean(GetReservationUseCase.class).execute(client, bad).status());
            assertEquals(0, stocks.findById(a).orElseThrow().getReserved());
            assertEquals(0, stocks.findById(b).orElseThrow().getReserved());
        });
    }

    @Test void skipsLockedReservationAndRetriesAfterLockIsReleased() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            context.getBean(ReserveStockUseCase.class).execute(client, order, request(product));
            expireFixtures(List.of(order));
            try (var blocker = dataSource.getConnection(); var statement = blocker.createStatement(); var executor = Executors.newSingleThreadExecutor()) {
                blocker.setAutoCommit(false);
                statement.executeQuery("SELECT order_id FROM " + schema + ".inventory_reservations WHERE order_id='" + order + "' FOR UPDATE").close();
                try {
                    var future = executor.submit(() -> context.getBean(ExpireReservationUseCase.class).execute(order));
                    assertFalse(future.get(3, TimeUnit.SECONDS));
                } finally { blocker.rollback(); }
            }
            context.getBean(ReservationExpirationJob.class).run();
            assertEquals(0, stocks.findById(product).orElseThrow().getReserved());
            assertEquals(InventoryReservationStatus.EXPIRED, context.getBean(GetReservationUseCase.class).execute(client, order).status());
        });
    }

    @Test void concurrentWorkersAndSweepsNeverReturnUnitsTwice() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            context.getBean(ReserveStockUseCase.class).execute(client, order, request(product));
            expireFixtures(List.of(order));
            var expire = context.getBean(ExpireReservationUseCase.class);
            List<Boolean> results = concurrently(List.of(() -> expire.execute(order), () -> expire.execute(order), () -> expire.execute(order)));
            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
            concurrently(List.of(() -> { context.getBean(ReservationExpirationJob.class).run(); return true; },
                    () -> { context.getBean(ReservationExpirationJob.class).run(); return true; }));
            assertEquals(0, stocks.findById(product).orElseThrow().getReserved());
            assertEquals(1, stocks.findById(product).orElseThrow().getOnHand());
            assertEquals(2L, stocks.findById(product).orElseThrow().getVersion());
        });
    }

    @Test void expirationConcurrentWithReleaseAndConfirmationHasSingleStockEffect() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            context.getBean(ReserveStockUseCase.class).execute(client, order, request(product));
            expireFixtures(List.of(order));
            var transition = context.getBean(TransitionReservationUseCase.class);
            var results = concurrently(List.of(
                    () -> { context.getBean(ExpireReservationUseCase.class).execute(order); return 200; },
                    () -> { transition.release(client, order); return 200; },
                    () -> { try { transition.confirm(client, order); return 200; } catch (ReservationStateConflictException expected) { return 409; } }));
            assertEquals(List.of(200, 200, 409), results);
            assertEquals(InventoryReservationStatus.EXPIRED, context.getBean(GetReservationUseCase.class).execute(client, order).status());
            assertEquals(0, stocks.findById(product).orElseThrow().getReserved());
            assertEquals(1, stocks.findById(product).orElseThrow().getOnHand());
            assertEquals(2L, stocks.findById(product).orElseThrow().getVersion());
        });
    }

    @Test void upgradesV3AndExpiresPersistedReservationWithoutRewritingExistingData() throws Exception {
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("3").load().migrate();
        var product = UUID.randomUUID(); var order = UUID.randomUUID();
        sql("INSERT INTO inventory_stock (product_id,on_hand,reserved,version) VALUES ('" + product + "',1,1,7)");
        sql("INSERT INTO inventory_reservations (order_id,service_client_id,status,expires_at,created_at,updated_at) VALUES ('" + order + "','shopsphere-order','ACTIVE',CURRENT_TIMESTAMP-INTERVAL '1 hour',CURRENT_TIMESTAMP-INTERVAL '2 hours',CURRENT_TIMESTAMP-INTERVAL '2 hours')");
        sql("INSERT INTO inventory_reservation_items VALUES ('" + order + "','" + product + "',1)");
        runner.run(context -> {
            assertEquals(7L, context.getBean(InventoryStockRepository.class).findById(product).orElseThrow().getVersion());
            assertEquals(InventoryReservationStatus.ACTIVE, context.getBean(GetReservationUseCase.class).execute(client, order).status());
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                    "SELECT indexname FROM pg_indexes WHERE schemaname=? AND indexname='idx_inventory_reservations_expiration'")) {
                statement.setString(1, schema); try (var result = statement.executeQuery()) { assertTrue(result.next()); }
            }
            context.getBean(ReservationExpirationJob.class).run();
            assertEquals(InventoryReservationStatus.EXPIRED, context.getBean(GetReservationUseCase.class).execute(client, order).status());
            assertEquals(0, context.getBean(InventoryStockRepository.class).findById(product).orElseThrow().getReserved());
            assertEquals(8L, context.getBean(InventoryStockRepository.class).findById(product).orElseThrow().getVersion());
        });
    }

    private ReserveStockRequest request(UUID product) {
        return new ReserveStockRequest(List.of(new ReserveStockRequest.Item(product, 1)));
    }
    private void expireFixtures(List<UUID> orders) throws Exception {
        var ids = orders.stream().map(order -> "'" + order + "'").collect(java.util.stream.Collectors.joining(","));
        sql("UPDATE inventory_reservations SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 hours', expires_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE order_id IN (" + ids + ")");
    }
    private void sql(String sql) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema); statement.executeUpdate(sql);
        }
    }
    private <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        var barrier = new CyclicBarrier(calls.size());
        try (var executor = Executors.newFixedThreadPool(calls.size())) {
            var futures = calls.stream().map(call -> executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return call.call(); })).toList();
            var outcomes = new ArrayList<T>(); for (var future : futures) outcomes.add(future.get(20, TimeUnit.SECONDS)); return outcomes;
        }
    }
}
