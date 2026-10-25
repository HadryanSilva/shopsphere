package br.com.hadryan.shosphere.inventory.persistence;

import br.com.hadryan.shosphere.inventory.InventoryServiceApplication;
import br.com.hadryan.shosphere.inventory.client.CatalogClient;
import br.com.hadryan.shosphere.inventory.controller.InventoryReservationController;
import br.com.hadryan.shosphere.inventory.exception.StockReservationConflictException;
import br.com.hadryan.shosphere.inventory.mapper.request.ReserveStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.request.AdjustStockRequest;
import br.com.hadryan.shosphere.inventory.model.InventoryStock;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.service.ReserveStockUseCase;
import br.com.hadryan.shosphere.inventory.service.GetReservationUseCase;
import br.com.hadryan.shosphere.inventory.service.AdjustStockUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.Duration;
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
class InventoryReservationCreationTests {
    private DriverManagerDataSource dataSource;
    private String schema;
    private ApplicationContextRunner runner;
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final String client = "shopsphere-order";

    @BeforeEach void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "inventory_creation_test_" + UUID.randomUUID().toString().replace("-", "");
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

    @Test void createsAtomicallyAndReplaysReorderedIntentWithoutRenewingExpiry() {
        runner.run(context -> {
            var a = UUID.randomUUID(); var b = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAllAndFlush(List.of(new InventoryStock(a, 10), new InventoryStock(b, Integer.MAX_VALUE)));
            var controller = context.getBean(InventoryReservationController.class);
            var jwt = Jwt.withTokenValue("test").header("alg", "RS256").claim("azp", client).build();
            var created = controller.reserve(jwt, order, request(a, 3, b, Integer.MAX_VALUE));
            assertEquals(201, created.getStatusCode().value());
            assertEquals("/internal/api/v1/inventory/reservations/" + order, created.getHeaders().getLocation().toString());
            var response = created.getBody(); assertNotNull(response);
            assertEquals(Duration.ofMinutes(15), Duration.between(response.createdAt(), response.expiresAt()));
            assertEquals(InventoryReservationStatus.ACTIVE, response.status());
            assertEquals(response, controller.get(jwt, order).getBody());
            var replay = controller.reserve(jwt, order, request(b, Integer.MAX_VALUE, a, 3));
            assertEquals(200, replay.getStatusCode().value()); assertEquals(response, replay.getBody());
            assertEquals(3, stocks.findById(a).orElseThrow().getReserved());
            assertEquals(10, stocks.findById(a).orElseThrow().getOnHand());
            assertEquals(Integer.MAX_VALUE, stocks.findById(b).orElseThrow().getReserved());
            assertEquals(1L, stocks.findById(a).orElseThrow().getVersion());
            assertEquals(1, context.getBean(InventoryReservationRepository.class).count());
            verifyNoInteractions(catalog);
        });
    }

    @Test void changedIntentConflictsAndAnotherClientSeesNeitherReadNorReplay() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 10));
            var useCase = context.getBean(ReserveStockUseCase.class);
            useCase.execute(client, order, request(product, 1));
            for (var intent : List.of(request(product, 2), request(UUID.randomUUID(), 1))) {
                assertEquals(409, assertThrows(ResponseStatusException.class, () -> useCase.execute(client, order, intent)).getStatusCode().value());
            }
            assertEquals(404, assertThrows(ResponseStatusException.class,
                    () -> useCase.execute("another-client", order, request(product, 1))).getStatusCode().value());
            var query = context.getBean(GetReservationUseCase.class);
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> query.execute("another-client", order)).getStatusCode().value());
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> query.execute(client, UUID.randomUUID())).getStatusCode().value());
            assertEquals(1, stocks.findById(product).orElseThrow().getReserved());
        });
    }

    @Test void insufficientOrMissingItemRollsBackAllCountersAndOrderCanBeRetried() {
        runner.run(context -> {
            var a = new UUID(0, 1); var b = new UUID(0, 2); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAllAndFlush(List.of(new InventoryStock(a, 1), new InventoryStock(b, 0)));
            var useCase = context.getBean(ReserveStockUseCase.class);
            assertThrows(StockReservationConflictException.class, () -> useCase.execute(client, order, request(a, 1, b, 1)));
            assertFalse(context.getBean(InventoryReservationRepository.class).existsById(order));
            assertEquals(0, stocks.findById(a).orElseThrow().getReserved());
            assertEquals(0L, stocks.findById(a).orElseThrow().getVersion());
            assertEquals(404, assertThrows(ResponseStatusException.class,
                    () -> useCase.execute(client, order, request(a, 1, UUID.randomUUID(), 1))).getStatusCode().value());
            assertFalse(context.getBean(InventoryReservationRepository.class).existsById(order));
            assertTrue(useCase.execute(client, order, request(a, 1)).created());
            assertEquals(1, stocks.findById(a).orElseThrow().getReserved());
        });
    }

    @Test void replayAndGetPreserveTerminalOrExpiredStatesWithoutReactivatingOrUpdating() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            context.getBean(InventoryStockRepository.class).saveAndFlush(new InventoryStock(product, 1));
            var useCase = context.getBean(ReserveStockUseCase.class);
            useCase.execute(client, order, request(product, 1));
            for (var status : InventoryReservationStatus.values()) {
                // Fixtures only: lifecycle effects are implemented in tasks 38–39.
                try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE " + schema + ".inventory_reservations SET status='" + status
                            + "', created_at=CURRENT_TIMESTAMP-INTERVAL '2 hours', expires_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE order_id='" + order + "'");
                }
                var before = context.getBean(GetReservationUseCase.class).execute(client, order);
                var replay = useCase.execute(client, order, request(product, 1));
                assertFalse(replay.created()); assertEquals(before, replay.reservation());
                assertEquals(status, replay.reservation().status());
            }
            var stock = context.getBean(InventoryStockRepository.class).findById(product).orElseThrow();
            assertEquals(1, stock.getReserved()); assertEquals(1L, stock.getVersion());
        });
    }

    @Test void createsOneHundredItemsAndDuplicatePayloadIsRejectedBeforePersisting() {
        runner.run(context -> {
            var products = java.util.stream.IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAllAndFlush(products.stream().map(product -> new InventoryStock(product, 1)).toList());
            var useCase = context.getBean(ReserveStockUseCase.class);
            var duplicateOrder = UUID.randomUUID();
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> useCase.execute(client, duplicateOrder, request(products.getFirst(), 1, products.getFirst(), 1))).getStatusCode().value());
            assertFalse(context.getBean(InventoryReservationRepository.class).existsById(duplicateOrder));
            var result = useCase.execute(client, duplicateOrder, new ReserveStockRequest(products.stream()
                    .map(product -> new ReserveStockRequest.Item(product, 1)).toList()));
            assertEquals(100, result.reservation().items().size());
            assertEquals(100, stocks.findAll().stream().mapToInt(InventoryStock::getReserved).sum());
        });
    }

    @Test void configurableTtlIsAppliedOnlyOnFirstCreation() {
        runner.withPropertyValues("inventory.reservations.ttl=PT2M").run(context -> {
            var product = UUID.randomUUID();
            context.getBean(InventoryStockRepository.class).saveAndFlush(new InventoryStock(product, 1));
            var result = context.getBean(ReserveStockUseCase.class).execute(client, UUID.randomUUID(), request(product, 1));
            assertEquals(Duration.ofMinutes(2), Duration.between(result.reservation().createdAt(), result.reservation().expiresAt()));
        });
    }

    @Test void concurrentIdenticalOrdersReserveOnceAndReturnOneCreation() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            var useCase = context.getBean(ReserveStockUseCase.class);
            var calls = new ArrayList<Callable<ReserveStockUseCase.Result>>();
            for (int i = 0; i < 4; i++) calls.add(() -> useCase.execute(client, order, request(product, 1)));
            var outcomes = concurrently(calls);
            assertEquals(1, outcomes.stream().filter(ReserveStockUseCase.Result::created).count());
            assertEquals(1, outcomes.stream().map(ReserveStockUseCase.Result::reservation).distinct().count());
            assertEquals(1, stocks.findById(product).orElseThrow().getReserved());
            assertEquals(1L, stocks.findById(product).orElseThrow().getVersion());
        });
    }

    @Test void concurrentDifferentIntentsForSameOrderHaveOneWinner() {
        runner.run(context -> {
            var a = UUID.randomUUID(); var b = UUID.randomUUID(); var order = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAllAndFlush(List.of(new InventoryStock(a, 1), new InventoryStock(b, 1)));
            var useCase = context.getBean(ReserveStockUseCase.class);
            var outcomes = concurrently(List.of(() -> attempt(useCase, order, request(a, 1)), () -> attempt(useCase, order, request(b, 1))));
            assertTrue(outcomes.contains(201)); assertTrue(outcomes.contains(409));
            assertEquals(1, stocks.findAll().stream().mapToInt(InventoryStock::getReserved).sum());
            assertEquals(1, context.getBean(InventoryReservationRepository.class).count());
        });
    }

    @Test void oppositeItemOrdersDoNotDeadlockOrReservePartially() {
        runner.run(context -> {
            var a = UUID.randomUUID(); var b = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAllAndFlush(List.of(new InventoryStock(a, 1), new InventoryStock(b, 1)));
            var useCase = context.getBean(ReserveStockUseCase.class);
            var outcomes = concurrently(List.of(() -> attempt(useCase, UUID.randomUUID(), request(a, 1, b, 1)),
                    () -> attempt(useCase, UUID.randomUUID(), request(b, 1, a, 1))));
            assertTrue(outcomes.contains(201)); assertTrue(outcomes.contains(409));
            assertEquals(2, stocks.findAll().stream().mapToInt(InventoryStock::getReserved).sum());
            assertEquals(1, context.getBean(InventoryReservationRepository.class).count());
        });
    }

    @Test void differentOrdersCompetingForLastUnitCannotOversell() {
        runner.run(context -> {
            var product = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            var useCase = context.getBean(ReserveStockUseCase.class);
            var calls = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < 4; i++) calls.add(() -> attempt(useCase, UUID.randomUUID(), request(product, 1)));
            var outcomes = concurrently(calls);
            assertEquals(1, outcomes.stream().filter(code -> code == 201).count());
            assertEquals(3, outcomes.stream().filter(code -> code == 409).count());
            assertEquals(1, stocks.findById(product).orElseThrow().getReserved());
            assertEquals(1, context.getBean(InventoryReservationRepository.class).count());
        });
    }

    @Test void adjustmentAndReservationUseSamePositionLock() {
        runner.run(context -> {
            var product = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            var outcomes = concurrently(List.of(
                    () -> attempt(context.getBean(ReserveStockUseCase.class), UUID.randomUUID(), request(product, 1)),
                    () -> {
                        try { context.getBean(AdjustStockUseCase.class).execute("admin", UUID.randomUUID(), product, new AdjustStockRequest(-1, "count")); return 201; }
                        catch (br.com.hadryan.shosphere.inventory.exception.StockAdjustmentConflictException expected) { return 409; }
                    }));
            assertTrue(outcomes.contains(201)); assertTrue(outcomes.contains(409));
            var stock = stocks.findById(product).orElseThrow();
            assertTrue(stock.getReserved() <= stock.getOnHand());
            assertEquals(0, stock.getAvailable());
        });
    }

    private int attempt(ReserveStockUseCase useCase, UUID order, ReserveStockRequest request) {
        try { return useCase.execute(client, order, request).created() ? 201 : 200; }
        catch (StockReservationConflictException expected) { return 409; }
        catch (ResponseStatusException expected) { return expected.getStatusCode().value(); }
    }

    private ReserveStockRequest request(UUID product, int quantity) {
        return new ReserveStockRequest(List.of(new ReserveStockRequest.Item(product, quantity)));
    }
    private ReserveStockRequest request(UUID a, int aQuantity, UUID b, int bQuantity) {
        return new ReserveStockRequest(List.of(new ReserveStockRequest.Item(a, aQuantity), new ReserveStockRequest.Item(b, bQuantity)));
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
