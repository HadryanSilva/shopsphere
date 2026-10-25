package br.com.hadryan.shosphere.inventory.persistence;

import br.com.hadryan.shosphere.inventory.InventoryServiceApplication;
import br.com.hadryan.shosphere.inventory.client.CatalogClient;
import br.com.hadryan.shosphere.inventory.exception.StockAdjustmentConflictException;
import br.com.hadryan.shosphere.inventory.mapper.request.*;
import br.com.hadryan.shosphere.inventory.mapper.response.AdjustmentResponse;
import br.com.hadryan.shosphere.inventory.model.InventoryStock;
import br.com.hadryan.shosphere.inventory.repository.*;
import br.com.hadryan.shosphere.inventory.service.*;
import br.com.hadryan.shosphere.inventory.service.impl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class InventoryAdjustmentPersistenceTests {
    DriverManagerDataSource dataSource;
    String schema;
    ApplicationContextRunner runner;
    CatalogClient catalog = mock(CatalogClient.class);
    @BeforeEach void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "inventory_adjustment_test_" + UUID.randomUUID().toString().replace("-", "");
        runner = new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(InventoryServiceApplication.class)
                .withBean(InventoryStartupPersistenceTests.InventoryTestComponentFilter.class, InventoryStartupPersistenceTests.InventoryTestComponentFilter::new)
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

    @Test void snapshotsReplayAndQueryRemainImmutableAndScoped() {
        runner.run(context -> {
            var stocks = context.getBean(InventoryStockRepository.class);
            var movements = context.getBean(InventoryMovementRepository.class);
            var useCase = context.getBean(AdjustStockUseCase.class);
            var query = context.getBean(GetStockAdjustmentUseCase.class);
            var product = UUID.randomUUID(); var key = UUID.randomUUID();
            context.getBean(RegisterStockUseCase.class).execute("admin", key, new RegisterStockRequest(product, 10));
            clearInvocations(catalog);
            var request = new AdjustStockRequest(-2, " count ");
            var created = useCase.execute("admin", key, product, request);
            var persisted = query.execute(product, created.id());
            assertEquals(created, persisted);
            assertEquals(8, persisted.onHandAfter());
            useCase.execute("admin", UUID.randomUUID(), product, new AdjustStockRequest(3, "restock"));
            assertEquals(persisted, useCase.execute("admin", key, product, request));
            assertEquals(11, stocks.findById(product).orElseThrow().getOnHand());
            for (var changed : List.of(new AdjustStockRequest(-1, " count "), new AdjustStockRequest(-2, "count"))) {
                assertEquals(409, assertThrows(ResponseStatusException.class,
                        () -> useCase.execute("admin", key, product, changed)).getStatusCode().value());
            }
            assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> useCase.execute("admin", key, UUID.randomUUID(), request)).getStatusCode().value());
            assertEquals(404, assertThrows(ResponseStatusException.class,
                    () -> query.execute(UUID.randomUUID(), created.id())).getStatusCode().value());
            var initial = movements.findAll().stream().filter(m -> m.getReason() == null).findFirst().orElseThrow();
            assertEquals(404, assertThrows(ResponseStatusException.class,
                    () -> query.execute(product, initial.getId())).getStatusCode().value());
            assertEquals(3, movements.count());
            verifyNoInteractions(catalog);
        });
    }

    @Test void invalidBalancesRollbackAndDoNotConsumeKey() {
        runner.run(context -> {
            var stocks = context.getBean(InventoryStockRepository.class);
            var product = UUID.randomUUID(); var key = UUID.randomUUID();
            stocks.saveAndFlush(new InventoryStock(product, 10));
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + schema + ".inventory_stock SET reserved=3 WHERE product_id='" + product + "'");
            }
            var useCase = context.getBean(AdjustStockUseCase.class);
            for (int delta : new int[]{-8, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                assertThrows(StockAdjustmentConflictException.class,
                        () -> useCase.execute("admin", key, product, new AdjustStockRequest(delta, "count")));
                assertEquals(10, stocks.findById(product).orElseThrow().getOnHand());
                assertEquals(0, context.getBean(InventoryMovementRepository.class).count());
            }
            var result = useCase.execute("admin", key, product, new AdjustStockRequest(-7, "count"));
            assertEquals(3, result.onHandAfter()); assertEquals(3, result.reservedAfter());
            assertEquals(1L, stocks.findById(product).orElseThrow().getVersion());
            verifyNoInteractions(catalog);
        });
    }

    @Test void concurrentReplaysOfLastUnitDebitOnlyOnceEvenAfterStalePrechecks() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var key = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            var barrier = new CyclicBarrier(4);
            var precheck = mock(StockAdjustmentIdempotency.class);
            when(precheck.findExisting(any(), any(), any(), any())).thenAnswer(inv -> {
                barrier.await(10, TimeUnit.SECONDS); return Optional.empty();
            });
            var useCase = new AdjustStockUseCaseImpl(precheck, context.getBean(AdjustStockTransaction.class));
            try (var executor = Executors.newFixedThreadPool(4)) {
                var futures = new ArrayList<Future<AdjustmentResponse>>();
                for (int i = 0; i < 4; i++) futures.add(executor.submit(() ->
                        useCase.execute("admin", key, product, new AdjustStockRequest(-1, "count"))));
                var ids = new HashSet<UUID>();
                for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS).id());
                assertEquals(1, ids.size());
            }
            assertEquals(0, stocks.findById(product).orElseThrow().getOnHand());
            assertEquals(1L, stocks.findById(product).orElseThrow().getVersion());
            assertEquals(1, context.getBean(InventoryMovementRepository.class).count());
        });
    }

    @Test void concurrentSameKeyAcrossProductsRollsBackLosingBalance() {
        runner.run(context -> {
            var stocks = context.getBean(InventoryStockRepository.class);
            var products = List.of(UUID.randomUUID(), UUID.randomUUID());
            products.forEach(product -> stocks.saveAndFlush(new InventoryStock(product, 10)));
            var key = UUID.randomUUID(); var barrier = new CyclicBarrier(2); var reads = new AtomicInteger();
            var real = context.getBean(StockAdjustmentIdempotency.class);
            var precheck = mock(StockAdjustmentIdempotency.class);
            when(precheck.findExisting(any(), any(), any(), any())).thenAnswer(inv -> {
                var result = real.findExisting(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
                if (reads.incrementAndGet() <= 2) barrier.await(10, TimeUnit.SECONDS);
                return result;
            });
            var useCase = new AdjustStockUseCaseImpl(precheck, context.getBean(AdjustStockTransaction.class));
            try (var executor = Executors.newFixedThreadPool(2)) {
                var futures = products.stream().map(product -> executor.submit(() -> {
                    try { useCase.execute("admin", key, product, new AdjustStockRequest(1, "count")); return 201; }
                    catch (ResponseStatusException conflict) { return conflict.getStatusCode().value(); }
                })).toList();
                var statuses = new ArrayList<Integer>();
                for (var future : futures) statuses.add(future.get(20, TimeUnit.SECONDS));
                Collections.sort(statuses); assertEquals(List.of(201, 409), statuses);
            }
            assertEquals(21, stocks.findAll().stream().mapToInt(InventoryStock::getOnHand).sum());
            assertEquals(1L, stocks.findAll().stream().mapToLong(InventoryStock::getVersion).sum());
            assertEquals(1, context.getBean(InventoryMovementRepository.class).count());
        });
    }

    @Test void concurrentDistinctDebitsCannotOversell() {
        runner.run(context -> {
            var product = UUID.randomUUID();
            var stocks = context.getBean(InventoryStockRepository.class);
            stocks.saveAndFlush(new InventoryStock(product, 1));
            var useCase = context.getBean(AdjustStockUseCase.class);
            var barrier = new CyclicBarrier(4); var successes = new AtomicInteger(); var conflicts = new AtomicInteger();
            try (var executor = Executors.newFixedThreadPool(4)) {
                var futures = new ArrayList<Future<?>>();
                for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try { useCase.execute("admin", UUID.randomUUID(), product, new AdjustStockRequest(-1, "count")); successes.incrementAndGet(); }
                    catch (StockAdjustmentConflictException expected) { conflicts.incrementAndGet(); }
                    return null;
                }));
                for (var future : futures) future.get(20, TimeUnit.SECONDS);
            }
            assertEquals(1, successes.get()); assertEquals(3, conflicts.get());
            assertEquals(0, stocks.findById(product).orElseThrow().getOnHand());
            assertEquals(1, context.getBean(InventoryMovementRepository.class).count());
        });
    }
}
