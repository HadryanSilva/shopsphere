package br.com.hadryan.shosphere.payment.persistence;

import br.com.hadryan.shosphere.payment.PaymentServiceApplication;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class PaymentStartupPersistenceTests {
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private String schema;
    private ApplicationContextRunner runner;
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach void setUp() {
        source = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        jdbc = new JdbcTemplate(source);
        schema = "payment_startup_test_" + UUID.randomUUID().toString().replace("-", "");
        runner = new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PaymentServiceApplication.class).withBean(TestFilter.class, TestFilter::new)
                .withBean(DataSource.class, () -> source).withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
                .withPropertyValues("spring.profiles.active=default", "spring.flyway.schemas=" + schema,
                        "spring.flyway.default-schema=" + schema, "spring.jpa.properties.hibernate.default_schema=" + schema);
    }
    @AfterEach void tearDown() {
        if (schema == null || !schema.matches("payment_startup_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test schema");
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
    private void insert() {
        jdbc.update("INSERT INTO " + schema + ".payments(order_id,service_client_id,status,amount,currency,created_at,updated_at) "
                + "VALUES (?,'shopsphere-order','PROCESSING',25.00,'EUR',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", orderId);
    }
    private void sql(String statement) { jdbc.execute(statement.replace("$schema", schema)); }
    private void rejects(String statement, String state) {
        var failure = assertThrows(org.springframework.dao.DataAccessException.class, () -> sql(statement));
        Throwable cause = failure;
        while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
        assertInstanceOf(SQLException.class, cause);
        assertEquals(state, ((SQLException) cause).getSQLState());
    }

    @Test void startsWithFlywayAndHibernateValidateAndRestartPreservesRowsWithoutReapplyingMigration() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var flyway = context.getBean(Flyway.class);
            assertEquals("1", flyway.info().current().getVersion().getVersion());
            assertEquals(0, flyway.info().pending().length);
            assertEquals("validate", context.getBean(EntityManagerFactory.class).getProperties().get("hibernate.hbm2ddl.auto"));
            insert();
        });
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, context.getBean(Flyway.class).migrate().migrationsExecuted);
            assertTrue(context.getBean(Flyway.class).validateWithResult().validationSuccessful);
            assertEquals(new BigDecimal("25.00"), jdbc.queryForObject("SELECT amount FROM " + schema + ".payments WHERE order_id=?", BigDecimal.class, orderId));
            assertEquals(List.of("flyway_schema_history", "payment_movements", "payments"), jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema=? ORDER BY table_name", String.class, schema));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".payment_movements", Long.class));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"amount=0", "amount=-1", "amount='NaN'::numeric", "currency='USD'", "currency='eur'", "service_client_id=' '", "version=-1",
            "status='UNKNOWN'", "status='CAPTURED'", "status='REFUNDED'", "cancel_requested=true", "updated_at=created_at-INTERVAL '1 second'"})
    void databaseRejectsInvalidIntentAndState(String change) {
        runner.run(context -> { assertNull(context.getStartupFailure()); insert();
            rejects("UPDATE $schema.payments SET " + change, "23514");
            assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM " + schema + ".payments", String.class));
        });
    }

    @Test void movementTermsAndNaturalIdentityPreventDuplicateOrIncompatibleFinancialRows() {
        runner.run(context -> {
            assertNull(context.getStartupFailure()); insert();
            sql("INSERT INTO $schema.payment_movements VALUES ('" + orderId + "','CAPTURE',25.00,'EUR',CURRENT_TIMESTAMP)");
            rejects("INSERT INTO $schema.payment_movements VALUES ('" + orderId + "','CAPTURE',25.00,'EUR',CURRENT_TIMESTAMP)", "23505");
            rejects("INSERT INTO $schema.payment_movements VALUES ('" + orderId + "','REFUND',24.00,'EUR',CURRENT_TIMESTAMP)", "23503");
            rejects("INSERT INTO $schema.payment_movements VALUES ('" + UUID.randomUUID() + "','CAPTURE',25.00,'EUR',CURRENT_TIMESTAMP)", "23503");
            rejects("INSERT INTO $schema.payment_movements VALUES ('" + orderId + "','OTHER',25.00,'EUR',CURRENT_TIMESTAMP)", "23514");
            rejects("INSERT INTO $schema.payment_movements VALUES ('" + orderId + "','REFUND','NaN'::numeric,'EUR',CURRENT_TIMESTAMP)", "23514");
            rejects("UPDATE $schema.payments SET amount=26.00", "23503");
            rejects("INSERT INTO $schema.payments SELECT * FROM $schema.payments", "23505");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".payment_movements", Long.class));
        });
    }

    @Test void monetaryRangeAndLifecycleTimestampsRemainExactAndRefundTotalIsRepresentable() {
        runner.run(context -> {
            assertNull(context.getStartupFailure()); insert();
            rejects("UPDATE $schema.payments SET amount=100000000000000000.00", "22003");
            sql("UPDATE $schema.payments SET amount=99999999999999999.99");
            assertEquals(new BigDecimal("99999999999999999.99"), jdbc.queryForObject("SELECT amount FROM " + schema + ".payments", BigDecimal.class));
            sql("UPDATE $schema.payments SET status='CAPTURED',captured_at=created_at");
            sql("UPDATE $schema.payments SET status='REFUND_PENDING',cancel_requested=true");
            sql("UPDATE $schema.payments SET status='REFUNDED',refunded_at=captured_at");
            rejects("UPDATE $schema.payments SET refunded_at=created_at-INTERVAL '1 second'", "23514");
            assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM " + schema + ".payments", String.class));
        });
    }

    // A context runner does not install SpringBootTest's exclusion of test-only controller/configuration components.
    static class TestFilter extends TypeExcludeFilter {
        @Override public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            return new AnnotationTypeFilter(TestComponent.class).match(reader, factory);
        }
        @Override public boolean equals(Object other) { return other != null && other.getClass() == getClass(); }
        @Override public int hashCode() { return getClass().hashCode(); }
    }
}
