package br.com.hadryan.shosphere.order.model;

import br.com.hadryan.shosphere.order.model.enums.OrderStatus;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class OrderSchemaMigrationTests {
    @Test
    void preservesLegacyOrdersAndPersistsCurrentSnapshotsWithHibernateValidation() throws Exception {
        var url = System.getenv("SHOPSPHERE_TEST_POSTGRES_URL");
        var username = System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres");
        var password = System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres");
        var dataSource = new DriverManagerDataSource(url, username, password);
        var schema = "order_schema_test_" + UUID.randomUUID().toString().replace("-", "");
        var legacyId = UUID.randomUUID();
        var completedId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("1").load().migrate();
            try (var connection = dataSource.getConnection()) {
                try (var insert = connection.prepareStatement("INSERT INTO " + schema
                        + ".orders (id, customer_id, status, total_amount) VALUES (?, ?, ?, ?)")) {
                    insert.setObject(1, legacyId);
                    insert.setObject(2, customerId);
                    insert.setString(3, "PENDING");
                    insert.setBigDecimal(4, new BigDecimal("25.00"));
                    insert.executeUpdate();
                    insert.setObject(1, completedId);
                    insert.setString(3, "COMPLETED");
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO " + schema
                        + ".order_items (id, order_id, product_id, product_name, quantity, unit_price, total_price)"
                        + " VALUES (?, ?, ?, 'Legacy product', 2, 12.50, 25.00)")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, legacyId);
                    insert.setObject(3, productId);
                    insert.executeUpdate();
                }
            }

            var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load();
            flyway.migrate();
            assertTrue(flyway.validateWithResult().validationSuccessful);

            var registry = new StandardServiceRegistryBuilder()
                    .applySetting("hibernate.connection.url", url)
                    .applySetting("hibernate.connection.username", username)
                    .applySetting("hibernate.connection.password", password)
                    .applySetting("hibernate.default_schema", schema)
                    .applySetting("hibernate.hbm2ddl.auto", "validate")
                    .applySetting("hibernate.physical_naming_strategy", PhysicalNamingStrategySnakeCaseImpl.class)
                    .build();
            try {
                try (var factory = new MetadataSources(registry).addAnnotatedClass(Order.class)
                        .addAnnotatedClass(OrderItem.class).addAnnotatedClass(ShippingAddress.class)
                        .buildMetadata().buildSessionFactory(); var session = factory.openSession()) {
                    var transaction = session.beginTransaction();
                    var legacy = session.find(Order.class, legacyId);
                    assertEquals(OrderStatus.PENDING, legacy.getStatus());
                    assertEquals(new BigDecimal("25.00"), legacy.getTotalAmount());
                    assertEquals(legacy.getTotalAmount(), legacy.getSubtotal());
                    assertEquals(new BigDecimal("0.00"), legacy.getDiscountAmount());
                    assertEquals(new BigDecimal("0.00"), legacy.getShippingAmount());
                    assertNull(legacy.getShippingAddress());
                    assertNull(legacy.getIdempotencyKey());
                    assertEquals(new BigDecimal("25.00"), legacy.getItems().getFirst().getLineTotal());
                    assertEquals(OrderStatus.COMPLETED, session.find(Order.class, completedId).getStatus());

                    var order = new Order();
                    order.setCustomerId(customerId);
                    order.setIdempotencyKey(UUID.randomUUID().toString());
                    order.setShippingAddress(new ShippingAddress("Customer", "Street 1", "Lisbon", "1000-001", "PT"));
                    order.addItem(productId, "Current product", new BigDecimal("12.50"), 2);
                    session.persist(order);
                    session.flush();
                    session.clear();

                    var saved = session.find(Order.class, order.getId());
                    assertEquals(OrderStatus.PENDING_STOCK, saved.getStatus());
                    assertEquals(new BigDecimal("25.00"), saved.getTotalAmount());
                    assertEquals(order.getIdempotencyKey(), saved.getIdempotencyKey());
                    assertEquals("PT", saved.getShippingAddress().getCountryCode());
                    assertEquals(new BigDecimal("25.00"), saved.getItems().getFirst().getLineTotal());
                    transaction.commit();
                }
            } finally {
                StandardServiceRegistryBuilder.destroy(registry);
            }
            assertEquals(0, flyway.migrate().migrationsExecuted);
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void duplicateExistingKeysBlockMigrationWithoutDeletingOrders() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        var schema = "order_duplicate_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("2").load().migrate();
            try (var connection = dataSource.getConnection(); var insert = connection.prepareStatement(
                    "INSERT INTO " + schema + ".orders (id, customer_id, idempotency_key, subtotal, total_amount)"
                            + " VALUES (?, ?, ?, 1.00, 1.00)")) {
                var customerId = UUID.randomUUID();
                var key = UUID.randomUUID().toString();
                for (int index = 0; index < 2; index++) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, customerId);
                    insert.setString(3, key);
                    insert.executeUpdate();
                }
            }
            var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load();
            assertThrows(org.flywaydb.core.api.FlywayException.class, flyway::migrate);
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT count(*) FROM " + schema + ".orders")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
