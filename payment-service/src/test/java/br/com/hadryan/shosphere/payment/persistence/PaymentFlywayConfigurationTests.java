package br.com.hadryan.shosphere.payment.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.*;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentFlywayConfigurationTests {
    private final DataSource source = mock(DataSource.class);
    private final FlywayMigrationStrategy strategy = mock(FlywayMigrationStrategy.class);
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withBean(DataSource.class, () -> source).withBean(FlywayMigrationStrategy.class, () -> strategy);
    }
    @Test void usesApplicationDatasourceAndValidateWithoutCreatingSchemaWithHibernate() {
        runner().run(context -> {
            assertNull(context.getStartupFailure());
            assertSame(source, context.getBean(Flyway.class).getConfiguration().getDataSource());
            assertEquals("validate", context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("false", context.getEnvironment().getProperty("spring.jpa.open-in-view"));
            verify(strategy).migrate(context.getBean(Flyway.class)); verifyNoInteractions(source);
        });
    }
    @Test void devSettingsCanBeSuppliedExternallyWithoutConnecting() {
        runner().withPropertyValues("spring.profiles.active=dev", "SHOPSPHERE_PAYMENT_PORT=18085",
                "SHOPSPHERE_PAYMENT_DB_URL=jdbc:postgresql://database.invalid:5432/payment_test",
                "SHOPSPHERE_PAYMENT_DB_USER=payment_test", "SHOPSPHERE_PAYMENT_DB_PASSWORD=test-only-value",
                "SHOPSPHERE_JWT_ISSUER=https://issuer.payment.test", "SHOPSPHERE_JWT_AUDIENCE=payment-test-api").run(context -> {
            assertNull(context.getStartupFailure()); var env = context.getEnvironment();
            assertEquals("18085", env.getProperty("server.port"));
            assertEquals("jdbc:postgresql://database.invalid:5432/payment_test", env.getProperty("spring.datasource.url"));
            assertEquals("payment_test", env.getProperty("spring.datasource.username"));
            assertEquals("test-only-value", env.getProperty("spring.datasource.password"));
            assertEquals("https://issuer.payment.test", env.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"));
            assertEquals("payment-test-api", env.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"));
            verifyNoInteractions(source);
        });
    }
}
