package br.com.hadryan.shosphere.inventory.persistence;

import br.com.hadryan.shosphere.inventory.InventoryServiceApplication;
import br.com.hadryan.shosphere.inventory.client.CatalogClient;
import br.com.hadryan.shosphere.inventory.exception.ReservationStateConflictException;
import br.com.hadryan.shosphere.inventory.exception.StockReservationConflictException;
import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.ReservationResponse;
import br.com.hadryan.shosphere.inventory.model.InventoryStock;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.service.GetReservationUseCase;
import br.com.hadryan.shosphere.inventory.service.ReserveStockUseCase;
import br.com.hadryan.shosphere.inventory.service.TransitionReservationUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class InventoryReservationTransitionTests {
    private DriverManagerDataSource dataSource;
    private String schema;
    private ApplicationContextRunner runner;
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final String client = "shopsphere-order";

    @BeforeEach void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "inventory_transition_test_" + UUID.randomUUID().toString().replace("-", "");
        runner = new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(InventoryServiceApplication.class)
                .withBean(InventoryStartupPersistenceTests.InventoryTestComponentFilter.class,
                        InventoryStartupPersistenceTests.InventoryTestComponentFilter::new)
                .withBean(DataSource.class, () -> dataSource).withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
                .withBean(CatalogClient.class, () -> catalog)
                .withPropertyValues("spring.flyway.schemas=" + schema, "spring.flyway.default-schema=" + schema,
                        "spring.jpa.properties.hibernate.default_schema=" + schema, "services.catalog.url=http://catalog.invalid");
    }

    @AfterEach void tearDown() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void confirmationConsumesOnceAndPreservesOtherReservationsAndSnapshots() {
        runner.run(context -> {
            var a = UUID.randomUUID(); var b = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAllAndFlush(List.of(new InventoryStock(a, 10), new InventoryStock(b, Integer.MAX_VALUE)));
            var reserve = context.getBean(ReserveStockUseCase.class);
            var request = new ReserveStockRequest(List.of(new ReserveStockRequest.Item(b, Integer.MAX_VALUE), new ReserveStockRequest.Item(a, 3)));
            var initial = reserve.execute(client, order, request).reservation();
            reserve.execute(client, UUID.randomUUID(), request(a, 2));
            var transition = context.getBean(TransitionReservationUseCase.class);
            var confirmed = transition.confirm(client, order);
            assertEquals(InventoryReservationStatus.CONFIRMED, confirmed.status());
            assertEquals(initial.items(), confirmed.items()); assertEquals(initial.expiresAt(), confirmed.expiresAt());
            assertEquals(initial.createdAt(), confirmed.createdAt());
            assertEquals(7, stocks.findById(a).orElseThrow().getOnHand());
            assertEquals(2, stocks.findById(a).orElseThrow().getReserved());
            assertEquals(0, stocks.findById(b).orElseThrow().getOnHand());
            assertEquals(0, stocks.findById(b).orElseThrow().getReserved());
            var stockVersion = stocks.findById(a).orElseThrow().getVersion();
            var reservationVersion = context.getBean(InventoryReservationRepository.class).findById(order).orElseThrow().getVersion();
            assertEquals(confirmed, transition.confirm(client, order));
            assertEquals(confirmed, reserve.execute(client, order, request).reservation());
            assertEquals(confirmed, context.getBean(GetReservationUseCase.class).execute(client, order));
            assertThrows(ReservationStateConflictException.class, () -> transition.release(client, order));
            assertEquals(stockVersion, stocks.findById(a).orElseThrow().getVersion());
            assertEquals(reservationVersion, context.getBean(InventoryReservationRepository.class).findById(order).orElseThrow().getVersion());
            verifyNoInteractions(catalog);
        });
    }

    @Test void releaseReturnsOnlyItsReservedUnitsAndCannotLaterConfirm() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 10));
            var reserve = context.getBean(ReserveStockUseCase.class);
            reserve.execute(client, order, request(product, 3));
            reserve.execute(client, UUID.randomUUID(), request(product, 2));
            var transition = context.getBean(TransitionReservationUseCase.class);
            var released = transition.release(client, order);
            assertEquals(InventoryReservationStatus.RELEASED, released.status());
            assertEquals(10, stocks.findById(product).orElseThrow().getOnHand());
            assertEquals(2, stocks.findById(product).orElseThrow().getReserved());
            var version = stocks.findById(product).orElseThrow().getVersion();
            assertEquals(released, transition.release(client, order));
            assertEquals(released, reserve.execute(client, order, request(product, 3)).reservation());
            assertThrows(ReservationStateConflictException.class, () -> transition.confirm(client, order));
            assertEquals(version, stocks.findById(product).orElseThrow().getVersion());
        });
    }

    @Test void expiredConfirmationDoesNotConsumeAndReleaseCommitsExpiryAndReturnsUnitsOnce() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            context.getBean(ReserveStockUseCase.class).execute(client, order, request(product, 1));
            expireFixture(order);
            var query = context.getBean(GetReservationUseCase.class);
            var before = query.execute(client, order);
            var transition = context.getBean(TransitionReservationUseCase.class);
            assertThrows(ReservationStateConflictException.class, () -> transition.confirm(client, order));
            assertEquals(before, query.execute(client, order));
            assertEquals(1, stocks.findById(product).orElseThrow().getReserved());
            var expired = transition.release(client, order);
            assertEquals(InventoryReservationStatus.EXPIRED, expired.status());
            assertEquals(1, stocks.findById(product).orElseThrow().getOnHand());
            assertEquals(0, stocks.findById(product).orElseThrow().getReserved());
            assertThrows(ReservationStateConflictException.class, () -> transition.confirm(client, order));
            assertEquals(expired, transition.release(client, order));
            assertEquals(2L, stocks.findById(product).orElseThrow().getVersion());
        });
    }

    @Test void rechecksDeadlineAfterWaitingForStockLock() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            context.getBean(ReserveStockUseCase.class).execute(client, order, request(product, 1));
            var expiry = Instant.now().plusSeconds(1);
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                    "UPDATE " + schema + ".inventory_reservations SET expires_at=? WHERE order_id=?")) {
                statement.setObject(1, expiry.atOffset(java.time.ZoneOffset.UTC)); statement.setObject(2, order); statement.executeUpdate();
            }
            try (var blocker = dataSource.getConnection(); var lock = blocker.createStatement();
                 var executor = Executors.newSingleThreadExecutor()) {
                blocker.setAutoCommit(false);
                lock.executeQuery("SELECT product_id FROM " + schema + ".inventory_stock WHERE product_id='" + product + "' FOR UPDATE").close();
                var future = executor.submit(() -> context.getBean(TransitionReservationUseCase.class).confirm(client, order));
                assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));
                while (Instant.now().isBefore(expiry.plusMillis(10))) Thread.sleep(20);
                blocker.commit();
                var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
                assertInstanceOf(ReservationStateConflictException.class, failure.getCause());
            }
            assertEquals(1, stocks.findById(product).orElseThrow().getOnHand());
            assertEquals(1, stocks.findById(product).orElseThrow().getReserved());
            assertEquals(InventoryReservationStatus.ACTIVE, context.getBean(GetReservationUseCase.class).execute(client, order).status());
        });
    }

    @Test void hidesMissingAndForeignReservationsForBothOperations() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            context.getBean(ReserveStockUseCase.class).execute(client, order, request(product, 1));
            var transition = context.getBean(TransitionReservationUseCase.class);
            for (var candidate : List.of(order, UUID.randomUUID())) {
                assertEquals(404, assertThrows(ResponseStatusException.class, () -> transition.confirm("another-client", candidate)).getStatusCode().value());
                assertEquals(404, assertThrows(ResponseStatusException.class, () -> transition.release("another-client", candidate)).getStatusCode().value());
            }
            assertEquals(1, stocks.findById(product).orElseThrow().getReserved());
            assertEquals(InventoryReservationStatus.ACTIVE, context.getBean(GetReservationUseCase.class).execute(client, order).status());
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void failureInLaterItemRollsBackEarlierItemAndState(boolean confirm) {
        runner.run(context -> {
            var a = new UUID(0, 1); var b = new UUID(0, 2); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAllAndFlush(List.of(new InventoryStock(a, 2), new InventoryStock(b, 2)));
            context.getBean(ReserveStockUseCase.class).execute(client, order,
                    new ReserveStockRequest(List.of(new ReserveStockRequest.Item(b, 1), new ReserveStockRequest.Item(a, 1))));
            // Corruption fixture only: fail after the first item to verify whole-transaction rollback.
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + schema + ".inventory_stock SET reserved=0 WHERE product_id='" + b + "'");
            }
            var transition = context.getBean(TransitionReservationUseCase.class);
            var before = context.getBean(GetReservationUseCase.class).execute(client, order);
            assertThrows(IllegalStateException.class, () -> {
                if (confirm) transition.confirm(client, order); else transition.release(client, order);
            });
            assertEquals(before, context.getBean(GetReservationUseCase.class).execute(client, order));
            assertEquals(2, stocks.findById(a).orElseThrow().getOnHand());
            assertEquals(1, stocks.findById(a).orElseThrow().getReserved());
            assertEquals(1L, stocks.findById(a).orElseThrow().getVersion());
        });
    }

    @Test void concurrentConfirmationsConsumeOnce() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            context.getBean(ReserveStockUseCase.class).execute(client, order, request(product, 1));
            var transition = context.getBean(TransitionReservationUseCase.class);
            var calls = new ArrayList<Callable<ReservationResponse>>();
            for (int i = 0; i < 4; i++) calls.add(() -> transition.confirm(client, order));
            var results = concurrently(calls);
            assertEquals(1, results.stream().distinct().count());
            assertEquals(0, stocks.findById(product).orElseThrow().getOnHand());
            assertEquals(0, stocks.findById(product).orElseThrow().getReserved());
            assertEquals(2L, stocks.findById(product).orElseThrow().getVersion());
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentReleasesReturnUnitsOnceEvenWhenExpired(boolean expired) {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            context.getBean(ReserveStockUseCase.class).execute(client, order, request(product, 1));
            if (expired) expireFixture(order);
            var transition = context.getBean(TransitionReservationUseCase.class);
            var calls = new ArrayList<Callable<ReservationResponse>>();
            for (int i = 0; i < 4; i++) calls.add(() -> transition.release(client, order));
            var results = concurrently(calls);
            assertEquals(1, results.stream().distinct().count());
            assertEquals(expired ? InventoryReservationStatus.EXPIRED : InventoryReservationStatus.RELEASED, results.getFirst().status());
            assertEquals(1, stocks.findById(product).orElseThrow().getOnHand());
            assertEquals(0, stocks.findById(product).orElseThrow().getReserved());
            assertEquals(2L, stocks.findById(product).orElseThrow().getVersion());
        });
    }

    @Test void confirmAndReleaseCannotBothWin() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            context.getBean(ReserveStockUseCase.class).execute(client, order, request(product, 1));
            var transition = context.getBean(TransitionReservationUseCase.class);
            var results = concurrently(List.of(() -> attempt(() -> transition.confirm(client, order)),
                    () -> attempt(() -> transition.release(client, order))));
            assertTrue(results.contains(200)); assertTrue(results.contains(409));
            var state = context.getBean(GetReservationUseCase.class).execute(client, order).status();
            var stock = stocks.findById(product).orElseThrow();
            assertEquals(0, stock.getReserved()); assertEquals(2L, stock.getVersion());
            assertEquals(state == InventoryReservationStatus.CONFIRMED ? 0 : 1, stock.getOnHand());
        });
    }

    @Test void transitionsOnOverlappingReservationsRespectSameUuidLockOrder() {
        runner.run(context -> {
            var a = UUID.randomUUID(); var b = UUID.randomUUID(); var first = UUID.randomUUID(); var second = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAllAndFlush(List.of(new InventoryStock(a, 2), new InventoryStock(b, 2)));
            var reserve = context.getBean(ReserveStockUseCase.class);
            reserve.execute(client, first, new ReserveStockRequest(List.of(new ReserveStockRequest.Item(a, 1), new ReserveStockRequest.Item(b, 1))));
            reserve.execute(client, second, new ReserveStockRequest(List.of(new ReserveStockRequest.Item(b, 1), new ReserveStockRequest.Item(a, 1))));
            var transition = context.getBean(TransitionReservationUseCase.class);
            var results = concurrently(List.of(() -> transition.confirm(client, first), () -> transition.release(client, second)));
            assertEquals(2, results.size());
            for (var stock : stocks.findAll()) { assertEquals(1, stock.getOnHand()); assertEquals(0, stock.getReserved()); }
        });
    }

    @Test void releaseAndNewReservationDoNotLoseOrDuplicateAvailability() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            var reserve = context.getBean(ReserveStockUseCase.class);
            reserve.execute(client, order, request(product, 1));
            var newOrder = UUID.randomUUID();
            var results = concurrently(List.of(() -> attempt(() -> context.getBean(TransitionReservationUseCase.class).release(client, order)),
                    () -> {
                        try { reserve.execute(client, newOrder, request(product, 1)); return 200; }
                        catch (StockReservationConflictException expected) { return 409; }
                    }));
            assertEquals(200, results.getFirst());
            var created = context.getBean(InventoryReservationRepository.class).existsById(newOrder);
            var stock = stocks.findById(product).orElseThrow();
            assertEquals(1, stock.getOnHand()); assertEquals(created ? 1 : 0, stock.getReserved());
            assertEquals(created ? 200 : 409, results.get(1));
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void transitionsAllHundredItemsAtomically(boolean confirm) {
        runner.run(context -> {
            var products = java.util.stream.IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAllAndFlush(products.stream().map(product -> new InventoryStock(product, 1)).toList());
            var order = UUID.randomUUID();
            context.getBean(ReserveStockUseCase.class).execute(client, order, new ReserveStockRequest(products.stream()
                    .map(product -> new ReserveStockRequest.Item(product, 1)).toList()));
            var transition = context.getBean(TransitionReservationUseCase.class);
            var result = confirm ? transition.confirm(client, order) : transition.release(client, order);
            assertEquals(100, result.items().size());
            for (var stock : stocks.findAll()) {
                assertEquals(confirm ? 0 : 1, stock.getOnHand()); assertEquals(0, stock.getReserved());
            }
        });
    }

    private ReserveStockRequest request(UUID product, int quantity) {
        return new ReserveStockRequest(List.of(new ReserveStockRequest.Item(product, quantity)));
    }
    private void expireFixture(UUID order) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + schema + ".inventory_reservations SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 hours', expires_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE order_id='" + order + "'");
        }
    }
    private int attempt(Callable<ReservationResponse> action) throws Exception {
        try { action.call(); return 200; }
        catch (ReservationStateConflictException expected) { return 409; }
    }
    private <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        var barrier = new CyclicBarrier(calls.size());
        try (var executor = Executors.newFixedThreadPool(calls.size())) {
            var futures = calls.stream().map(call -> executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS); return call.call();
            })).toList();
            var outcomes = new ArrayList<T>();
            for (var future : futures) outcomes.add(future.get(20, TimeUnit.SECONDS));
            return outcomes;
        }
    }
}
