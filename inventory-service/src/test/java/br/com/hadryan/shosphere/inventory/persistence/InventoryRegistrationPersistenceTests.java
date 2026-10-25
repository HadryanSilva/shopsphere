package br.com.hadryan.shosphere.inventory.persistence;

import br.com.hadryan.shosphere.inventory.InventoryServiceApplication;
import br.com.hadryan.shosphere.inventory.client.CatalogClient;
import br.com.hadryan.shosphere.inventory.controller.InventoryController;
import br.com.hadryan.shosphere.inventory.mapper.request.RegisterStockRequest;
import br.com.hadryan.shosphere.inventory.mapper.response.StockResponse;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryMovementType;
import br.com.hadryan.shosphere.inventory.repository.InventoryMovementRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.service.RegisterStockUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class InventoryRegistrationPersistenceTests {
    private DriverManagerDataSource dataSource;
    private String schema;
    private ApplicationContextRunner runner;
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final String actor = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "inventory_registration_test_" + UUID.randomUUID().toString().replace("-", "");
        runner = new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(InventoryServiceApplication.class)
                .withBean(InventoryStartupPersistenceTests.InventoryTestComponentFilter.class,
                        InventoryStartupPersistenceTests.InventoryTestComponentFilter::new)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
                .withBean(CatalogClient.class, () -> catalog)
                .withPropertyValues("spring.flyway.schemas=" + schema, "spring.flyway.default-schema=" + schema,
                        "spring.jpa.properties.hibernate.default_schema=" + schema,
                        "services.catalog.url=http://catalog.invalid");
        allowCatalog();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (schema != null) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, Integer.MAX_VALUE})
    void registersStockAndInitialMovementInOneCommit(int quantity) {
        runner.run(context -> {
            var productId = UUID.randomUUID();
            var key = UUID.randomUUID();
            var jwt = Jwt.withTokenValue("test-token").header("alg", "RS256").subject(actor).build();
            var created = context.getBean(InventoryController.class).register(jwt, key, new RegisterStockRequest(productId, quantity));
            assertEquals(201, created.getStatusCode().value());
            assertEquals("/api/v1/inventory/items/" + productId, created.getHeaders().getLocation().toString());
            var result = created.getBody();
            assertNotNull(result);

            assertEquals(productId, result.productId());
            assertEquals(quantity, result.onHand());
            assertEquals(0, result.reserved());
            assertEquals(quantity, result.available());
            assertEquals(0L, result.version());
            var movement = context.getBean(InventoryMovementRepository.class)
                    .findByActorSubjectAndMovementTypeAndIdempotencyKey(actor, InventoryMovementType.INITIAL, key).orElseThrow();
            assertEquals(productId, movement.getProductId());
            assertEquals(quantity, movement.getDelta());
            assertEquals(quantity, movement.getOnHandAfter());
            assertEquals(0, movement.getReservedAfter());
            assertNull(movement.getReason());
            assertNotNull(movement.getCreatedAt());
            assertCounts(context, 1, 1);
            verify(catalog).requireProduct(productId);
        });
    }

    @Test
    void replayUsesOriginalPayloadAndReturnsCurrentStockWithoutCatalog() {
        runner.run(context -> {
            var useCase = context.getBean(RegisterStockUseCase.class);
            var stocks = context.getBean(InventoryStockRepository.class);
            var request = new RegisterStockRequest(UUID.randomUUID(), 7);
            var key = UUID.randomUUID();
            useCase.execute(actor, key, request);
            var stock = stocks.findById(request.productId()).orElseThrow();
            ReflectionTestUtils.setField(stock, "onHand", 9);
            stocks.saveAndFlush(stock);
            clearInvocations(catalog);

            var replay = useCase.execute(actor, key, request);
            assertEquals(9, replay.onHand());
            assertEquals(1L, replay.version());
            var changedQuantity = assertThrows(ResponseStatusException.class,
                    () -> useCase.execute(actor, key, new RegisterStockRequest(request.productId(), 9)));
            assertEquals(409, changedQuantity.getStatusCode().value());
            var changedProduct = assertThrows(ResponseStatusException.class,
                    () -> useCase.execute(actor, key, new RegisterStockRequest(UUID.randomUUID(), 7)));
            assertEquals(409, changedProduct.getStatusCode().value());
            verifyNoInteractions(catalog);
            assertCounts(context, 1, 1);
        });
    }

    @Test
    void duplicateProductDoesNotReserveNewKeyOrCallCatalog() {
        runner.run(context -> {
            var useCase = context.getBean(RegisterStockUseCase.class);
            var request = new RegisterStockRequest(UUID.randomUUID(), 7);
            useCase.execute(actor, UUID.randomUUID(), request);
            var unusedKey = UUID.randomUUID();
            clearInvocations(catalog);

            var conflict = assertThrows(ResponseStatusException.class, () -> useCase.execute(actor, unusedKey, request));
            assertEquals(409, conflict.getStatusCode().value());
            verifyNoInteractions(catalog);
            useCase.execute(actor, unusedKey, new RegisterStockRequest(UUID.randomUUID(), 1));
            assertCounts(context, 2, 2);
        });
    }

    @Test
    void sameKeyIsIndependentBetweenActors() {
        runner.run(context -> {
            var useCase = context.getBean(RegisterStockUseCase.class);
            var key = UUID.randomUUID();
            useCase.execute(actor, key, new RegisterStockRequest(UUID.randomUUID(), 1));
            useCase.execute(UUID.randomUUID().toString(), key, new RegisterStockRequest(UUID.randomUUID(), 2));
            assertCounts(context, 2, 2);
        });
    }

    @Test
    void catalogFailureCreatesNeitherStockNorKeyAndCanBeRetried() {
        runner.run(context -> {
            var useCase = context.getBean(RegisterStockUseCase.class);
            var request = new RegisterStockRequest(UUID.randomUUID(), 1);
            var key = UUID.randomUUID();
            doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"))
                    .when(catalog).requireProduct(request.productId());

            assertEquals(404, assertThrows(ResponseStatusException.class,
                    () -> useCase.execute(actor, key, request)).getStatusCode().value());
            assertCounts(context, 0, 0);
            allowCatalog();
            useCase.execute(actor, key, request);
            assertCounts(context, 1, 1);
        });
    }

    @Test
    void concurrentIdenticalRegistrationsCommitOnlyOnce() {
        runner.run(context -> {
            var request = new RegisterStockRequest(UUID.randomUUID(), 7);
            var key = UUID.randomUUID();
            var calls = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(index -> new Call(actor, key, request)).toList();
            var outcomes = concurrently(context.getBean(RegisterStockUseCase.class), calls);
            for (var outcome : outcomes) {
                assertNull(outcome.failureStatus());
                assertEquals(request.productId(), outcome.response().productId());
                assertEquals(7, outcome.response().onHand());
            }
            assertCounts(context, 1, 1);
        });
    }

    @Test
    void concurrentSameKeyWithDifferentProductsRollsBackLosingStock() {
        runner.run(context -> {
            var key = UUID.randomUUID();
            var calls = List.of(new Call(actor, key, new RegisterStockRequest(UUID.randomUUID(), 7)),
                    new Call(actor, key, new RegisterStockRequest(UUID.randomUUID(), 9)));
            var outcomes = concurrently(context.getBean(RegisterStockUseCase.class), calls);
            assertEquals(1, outcomes.stream().filter(outcome -> outcome.response() != null).count());
            assertEquals(1, outcomes.stream().filter(outcome -> Integer.valueOf(409).equals(outcome.failureStatus())).count());
            assertCounts(context, 1, 1);
        });
    }

    @Test
    void concurrentDifferentKeysForSameProductDoNotBurnLosingKey() {
        runner.run(context -> {
            var useCase = context.getBean(RegisterStockUseCase.class);
            var request = new RegisterStockRequest(UUID.randomUUID(), 7);
            var calls = List.of(new Call(actor, UUID.randomUUID(), request), new Call(actor, UUID.randomUUID(), request));
            var outcomes = concurrently(useCase, calls);
            assertEquals(1, outcomes.stream().filter(outcome -> outcome.response() != null).count());
            var loser = outcomes.get(0).response() == null ? 0 : 1;
            assertEquals(409, outcomes.get(loser).failureStatus());
            assertCounts(context, 1, 1);
            useCase.execute(actor, calls.get(loser).key(), new RegisterStockRequest(UUID.randomUUID(), 1));
            assertCounts(context, 2, 2);
        });
    }

    private List<Outcome> concurrently(RegisterStockUseCase useCase, List<Call> calls) throws Exception {
        var ready = new CyclicBarrier(calls.size());
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            ready.await(10, TimeUnit.SECONDS);
            return null;
        }).when(catalog).requireProduct(any());
        try (var executor = Executors.newFixedThreadPool(calls.size())) {
            var futures = calls.stream().map(call -> executor.submit(() -> {
                try { return new Outcome(useCase.execute(call.actor(), call.key(), call.request()), null); }
                catch (ResponseStatusException failure) { return new Outcome(null, failure.getStatusCode().value()); }
            })).toList();
            var outcomes = new ArrayList<Outcome>();
            for (var future : futures) outcomes.add(future.get(20, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            allowCatalog();
        }
    }

    private void allowCatalog() {
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Catalog must be called outside a transaction");
            return null;
        }).when(catalog).requireProduct(any());
    }

    private void assertCounts(ApplicationContext context, int stocks, int movements) {
        assertEquals(stocks, context.getBean(InventoryStockRepository.class).count());
        assertEquals(movements, context.getBean(InventoryMovementRepository.class).count());
    }

    private record Call(String actor, UUID key, RegisterStockRequest request) {}
    private record Outcome(StockResponse response, Integer failureStatus) {}
}
