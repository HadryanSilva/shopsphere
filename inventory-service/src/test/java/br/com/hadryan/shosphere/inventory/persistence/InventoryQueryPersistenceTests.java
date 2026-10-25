package br.com.hadryan.shosphere.inventory.persistence;

import br.com.hadryan.shosphere.inventory.InventoryServiceApplication;
import br.com.hadryan.shosphere.inventory.client.CatalogClient;
import br.com.hadryan.shosphere.inventory.model.InventoryStock;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryMovementRepository;
import br.com.hadryan.shosphere.inventory.service.GetStockUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class InventoryQueryPersistenceTests {
    private DriverManagerDataSource dataSource;
    private String schema;
    private ApplicationContextRunner runner;
    private final CatalogClient catalog = mock(CatalogClient.class);

    @BeforeEach void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "inventory_query_test_" + UUID.randomUUID().toString().replace("-", "");
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

    @Test void readsPersistedBalancesWithoutMutationOrCatalogAndDistinguishesMissing() {
        runner.run(context -> {
            var stocks = context.getBean(InventoryStockRepository.class);
            var query = context.getBean(GetStockUseCase.class);
            var zero = UUID.randomUUID(); var reserved = UUID.randomUUID(); var missing = UUID.randomUUID();
            stocks.saveAndFlush(new InventoryStock(zero, 0));
            stocks.saveAndFlush(new InventoryStock(reserved, Integer.MAX_VALUE));
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + schema + ".inventory_stock SET reserved=7 WHERE product_id='" + reserved + "'");
            }
            var before = stocks.findById(reserved).orElseThrow();
            var result = query.execute(reserved);
            assertEquals(Integer.MAX_VALUE, result.onHand());
            assertEquals(7, result.reserved());
            assertEquals(Integer.MAX_VALUE - 7, result.available());
            assertEquals(before.getVersion(), result.version());
            assertEquals(before.getUpdatedAt(), result.updatedAt());
            assertEquals(0, query.execute(zero).available());
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> query.execute(missing)).getStatusCode().value());
            var batch = query.executeBatch(List.of(missing, zero, reserved));
            assertEquals(2, batch.size());
            assertEquals(java.util.Set.of(zero, reserved), batch.stream().map(r -> r.productId()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(batch.contains(result));
            assertTrue(query.executeBatch(List.of(missing)).isEmpty());
            var after = stocks.findById(reserved).orElseThrow();
            assertEquals(before.getVersion(), after.getVersion());
            assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
            assertEquals(0, context.getBean(InventoryMovementRepository.class).count());
            verifyNoInteractions(catalog);
        });
    }

    @Test void batchLoadsAllHundredPositionsAndRejectsDuplicateIntent() {
        runner.run(context -> {
            var stocks = context.getBean(InventoryStockRepository.class);
            var query = context.getBean(GetStockUseCase.class);
            var ids = IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
            stocks.saveAllAndFlush(ids.stream().map(id -> new InventoryStock(id, 1)).toList());
            assertEquals(100, query.executeBatch(ids).size());
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> query.executeBatch(List.of(ids.getFirst(), ids.getFirst()))).getStatusCode().value());
            verifyNoInteractions(catalog);
        });
    }
}
