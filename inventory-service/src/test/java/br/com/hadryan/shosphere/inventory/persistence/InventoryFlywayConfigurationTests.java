package br.com.hadryan.shosphere.inventory.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InventoryFlywayConfigurationTests {
    private final DataSource dataSource = mock(DataSource.class);
    private final FlywayMigrationStrategy strategy = mock(FlywayMigrationStrategy.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
            .withBean(DataSource.class, () -> dataSource)
            .withBean(FlywayMigrationStrategy.class, () -> strategy);

    @Test
    void initializesFlywayWithApplicationDataSource() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Flyway.class)
                    .hasSingleBean(FlywayMigrationInitializer.class);
            var flyway = context.getBean(Flyway.class);
            assertThat(flyway.getConfiguration().getDataSource()).isSameAs(dataSource);
            assertThat(flyway.getConfiguration().getLocations()).extracting(Object::toString)
                    .containsExactly("classpath:db/migration");
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            verify(strategy).migrate(flyway);
            assertThat(new ClassPathResource("db/migration/V1__create_inventory_stock.sql").exists()).isTrue();
            verifyNoInteractions(dataSource);
        });
    }

    @Test
    void testProfileDisablesFlywayWithoutConnecting() {
        runner.withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(Flyway.class)
                    .doesNotHaveBean(FlywayMigrationInitializer.class);
            verifyNoInteractions(strategy, dataSource);
        });
    }

    @Test
    void devProfileAllowsExternalConfigurationWithoutConnecting() {
        runner.withPropertyValues("spring.profiles.active=dev",
                "SHOPSPHERE_INVENTORY_PORT=18084",
                "SHOPSPHERE_INVENTORY_DB_URL=jdbc:postgresql://database.invalid:5432/inventory_test",
                "SHOPSPHERE_INVENTORY_DB_USER=inventory_test",
                "SHOPSPHERE_INVENTORY_DB_PASSWORD=local-test-value",
                "SHOPSPHERE_JWT_ISSUER=https://issuer.inventory.test",
                "SHOPSPHERE_JWT_AUDIENCE=inventory-test-api").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Flyway.class);
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(18084);
            assertThat(environment.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://database.invalid:5432/inventory_test");
            assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("inventory_test");
            assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("local-test-value");
            assertThat(environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                    .isEqualTo("https://issuer.inventory.test");
            assertThat(environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"))
                    .isEqualTo("inventory-test-api");
            verifyNoInteractions(dataSource);
        });
    }
}
