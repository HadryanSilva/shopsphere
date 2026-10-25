package br.com.hadryan.shosphere.inventory.persistence;

import br.com.hadryan.shosphere.inventory.InventoryServiceApplication;
import br.com.hadryan.shosphere.inventory.client.CatalogClient;
import br.com.hadryan.shosphere.inventory.model.InventoryReservation;
import br.com.hadryan.shosphere.inventory.model.InventoryReservationItem;
import br.com.hadryan.shosphere.inventory.model.InventoryStock;
import br.com.hadryan.shosphere.inventory.model.enums.InventoryReservationStatus;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class InventoryReservationPersistenceTests {
    private DriverManagerDataSource dataSource;
    private String schema;
    private ApplicationContextRunner runner;
    private final CatalogClient catalog = mock(CatalogClient.class);

    @BeforeEach void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "inventory_reservation_test_" + UUID.randomUUID().toString().replace("-", "");
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

    @Test void persistsAllItemsAndOwnerAcrossRestartWithoutChangingStock() {
        var order = UUID.randomUUID(); var product = UUID.randomUUID();
        var expiry = Instant.now().plusSeconds(900).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        for (int startup = 0; startup < 2; startup++) {
            boolean first = startup == 0;
            runner.run(context -> {
                var reservations = context.getBean(InventoryReservationRepository.class);
                var stocks = context.getBean(InventoryStockRepository.class);
                if (first) {
                    stocks.saveAndFlush(new InventoryStock(product, Integer.MAX_VALUE));
                    reservations.saveAndFlush(new InventoryReservation(order, "shopsphere-order", expiry,
                            List.of(new InventoryReservationItem(product, Integer.MAX_VALUE))));
                }
                var stored = reservations.findByOrderIdAndServiceClientId(order, "shopsphere-order").orElseThrow();
                assertEquals(InventoryReservationStatus.ACTIVE, stored.getStatus());
                assertEquals(0L, stored.getVersion());
                assertEquals(expiry, stored.getExpiresAt());
                assertNotNull(stored.getCreatedAt());
                assertEquals(stored.getCreatedAt(), stored.getUpdatedAt());
                assertEquals(product, stored.getItems().getFirst().getProductId());
                assertEquals(Integer.MAX_VALUE, stored.getItems().getFirst().getQuantity());
                assertTrue(reservations.findByOrderIdAndServiceClientId(order, "another-client").isEmpty());
                var stock = stocks.findById(product).orElseThrow();
                assertEquals(0, stock.getReserved()); assertEquals(0L, stock.getVersion());
                assertEquals(0, context.getBean(Flyway.class).migrate().migrationsExecuted);
                verifyNoInteractions(catalog);
            });
        }
    }

    @Test void persistsAllApprovedStatusesAndRejectsStaleStateWrite() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            context.getBean(InventoryStockRepository.class).saveAndFlush(new InventoryStock(product, 1));
            var reservations = context.getBean(InventoryReservationRepository.class);
            reservations.saveAndFlush(reservation(order, product));
            var first = reservations.findByOrderIdAndServiceClientId(order, "shopsphere-order").orElseThrow();
            var stale = reservations.findByOrderIdAndServiceClientId(order, "shopsphere-order").orElseThrow();
            // Persistence test only: lifecycle rules and stock effects belong to tasks 38–39.
            ReflectionTestUtils.setField(first, "status", InventoryReservationStatus.CONFIRMED);
            reservations.saveAndFlush(first);
            ReflectionTestUtils.setField(stale, "status", InventoryReservationStatus.RELEASED);
            assertThrows(OptimisticLockingFailureException.class, () -> reservations.saveAndFlush(stale));
            assertEquals(InventoryReservationStatus.CONFIRMED, reservations.findById(order).orElseThrow().getStatus());
            for (var status : InventoryReservationStatus.values()) {
                try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                        "UPDATE " + schema + ".inventory_reservations SET status=? WHERE order_id=?")) {
                    statement.setString(1, status.name()); statement.setObject(2, order); statement.executeUpdate();
                }
                var stored = reservations.findByOrderIdAndServiceClientId(order, "shopsphere-order").orElseThrow();
                assertEquals(status, stored.getStatus()); assertEquals(1L, stored.getVersion());
                assertEquals(1, stored.getItems().size());
            }
        });
    }

    @Test void duplicateOrderCannotReplaceOwnerOrIntentAndMissingStockRollsBackParent() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            context.getBean(InventoryStockRepository.class).saveAndFlush(new InventoryStock(product, 1));
            var reservations = context.getBean(InventoryReservationRepository.class);
            reservations.saveAndFlush(reservation(order, product));
            assertThrows(DataIntegrityViolationException.class, () -> reservations.saveAndFlush(
                    new InventoryReservation(order, "another-client", Instant.now().plusSeconds(900),
                            List.of(new InventoryReservationItem(product, 2)))));
            assertEquals(1, reservations.findByOrderIdAndServiceClientId(order, "shopsphere-order")
                    .orElseThrow().getItems().getFirst().getQuantity());
            var missingOrder = UUID.randomUUID();
            assertThrows(DataIntegrityViolationException.class,
                    () -> reservations.saveAndFlush(reservation(missingOrder, UUID.randomUUID())));
            assertFalse(reservations.existsById(missingOrder));
            assertEquals(1, reservations.count());
        });
    }

    @Test void persistsOneHundredDistinctProducts() {
        runner.run(context -> {
            var products = IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
            context.getBean(InventoryStockRepository.class).saveAllAndFlush(
                    products.stream().map(id -> new InventoryStock(id, 1)).toList());
            var order = UUID.randomUUID(); var reservations = context.getBean(InventoryReservationRepository.class);
            reservations.saveAndFlush(new InventoryReservation(order, "shopsphere-order", Instant.now().plusSeconds(900),
                    products.stream().map(id -> new InventoryReservationItem(id, 1)).toList()));
            assertEquals(100, reservations.findByOrderIdAndServiceClientId(order, "shopsphere-order").orElseThrow().getItems().size());
        });
    }

    @Test void upgradesV2WithoutChangingExistingStockOrMovements() throws Exception {
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("2").load().migrate();
        var product = UUID.randomUUID(); var movement = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + schema + ".inventory_stock (product_id,on_hand,reserved,version) VALUES ('" + product + "',10,3,4)");
            statement.executeUpdate("INSERT INTO " + schema + ".inventory_movements (id,movement_type,product_id,delta,actor_subject,idempotency_key,on_hand_after,reserved_after) VALUES ('"
                    + movement + "','INITIAL','" + product + "',10,'admin','" + UUID.randomUUID() + "',10,0)");
        }
        runner.run(context -> {
            var stock = context.getBean(InventoryStockRepository.class).findById(product).orElseThrow();
            assertEquals(10, stock.getOnHand()); assertEquals(3, stock.getReserved()); assertEquals(4L, stock.getVersion());
            var stored = context.getBean(br.com.hadryan.shosphere.inventory.repository.InventoryMovementRepository.class)
                    .findById(movement).orElseThrow();
            assertEquals(10, stored.getDelta()); assertEquals(10, stored.getOnHandAfter()); assertEquals("admin", stored.getActorSubject());
            assertEquals(0, context.getBean(InventoryReservationRepository.class).count());
            assertEquals("4", context.getBean(Flyway.class).info().current().getVersion().toString());
        });
    }

    @ParameterizedTest
    @CsvSource({"0,23514", "-1,23514", "2147483648,22003"})
    void databaseRejectsInvalidItemQuantities(long quantity, String sqlState) {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            context.getBean(InventoryStockRepository.class).saveAndFlush(new InventoryStock(product, 1));
            context.getBean(InventoryReservationRepository.class).saveAndFlush(reservation(order, product));
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                    "UPDATE " + schema + ".inventory_reservation_items SET quantity=? WHERE order_id=?")) {
                statement.setLong(1, quantity); statement.setObject(2, order);
                assertEquals(sqlState, assertThrows(SQLException.class, statement::executeUpdate).getSQLState());
            }
        });
    }

    @Test void databaseEnforcesKeysOwnerStatusAndExpiry() {
        runner.run(context -> {
            var product = UUID.randomUUID(); var order = UUID.randomUUID();
            context.getBean(InventoryStockRepository.class).saveAndFlush(new InventoryStock(product, 1));
            context.getBean(InventoryReservationRepository.class).saveAndFlush(reservation(order, product));
            for (var sql : List.of(
                    "UPDATE inventory_reservations SET status='UNKNOWN' WHERE order_id='" + order + "'",
                    "UPDATE inventory_reservations SET service_client_id=' ' WHERE order_id='" + order + "'",
                    "UPDATE inventory_reservations SET expires_at=created_at WHERE order_id='" + order + "'")) {
                assertSqlState(sql, "23514");
            }
            assertSqlState("UPDATE inventory_reservations SET status=NULL WHERE order_id='" + order + "'", "23502");
            assertSqlState("INSERT INTO inventory_reservation_items VALUES ('" + order + "','" + product + "',1)", "23505");
            assertSqlState("INSERT INTO inventory_reservation_items VALUES ('" + UUID.randomUUID() + "','" + product + "',1)", "23503");
            assertSqlState("INSERT INTO inventory_reservation_items VALUES ('" + order + "','" + UUID.randomUUID() + "',1)", "23503");
        });
    }

    private InventoryReservation reservation(UUID order, UUID product) {
        return new InventoryReservation(order, "shopsphere-order", Instant.now().plusSeconds(900),
                List.of(new InventoryReservationItem(product, 1)));
    }

    private void assertSqlState(String sql, String state) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            assertEquals(state, assertThrows(SQLException.class, () -> statement.executeUpdate(sql)).getSQLState());
        }
    }
}
