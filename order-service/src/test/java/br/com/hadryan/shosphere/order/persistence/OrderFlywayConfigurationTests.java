package br.com.hadryan.shosphere.order.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderFlywayConfigurationTests {
    private final DataSource dataSource = mock(DataSource.class);
    private final FlywayMigrationStrategy strategy = mock(FlywayMigrationStrategy.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
            .withBean(DataSource.class, () -> dataSource)
            // Observe startup initialization without connecting to a database in these configuration tests.
            .withBean(FlywayMigrationStrategy.class, () -> strategy);

    @Test
    void enablesFlywayAndInvokesMigrationDuringContextStartup() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Flyway.class)
                    .hasSingleBean(FlywayMigrationInitializer.class);
            var flyway = context.getBean(Flyway.class);
            verify(strategy).migrate(flyway);
            assertThat(flyway.getConfiguration().getDataSource()).isSameAs(dataSource);
            assertThat(flyway.getConfiguration().getLocations()).extracting(Object::toString)
                    .containsExactly("classpath:db/migration");
            assertThat(new ClassPathResource("db/migration/V1__create_initial_schema.sql").exists()).isTrue();
        });
    }

    @Test
    void supportsExplicitlyDisablingMigrations() {
        runner.withPropertyValues("spring.flyway.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(Flyway.class)
                    .doesNotHaveBean(FlywayMigrationInitializer.class);
            verifyNoInteractions(strategy, dataSource);
        });
    }
}
