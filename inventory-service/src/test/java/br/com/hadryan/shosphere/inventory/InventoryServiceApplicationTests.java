package br.com.hadryan.shosphere.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import br.com.hadryan.shosphere.inventory.repository.InventoryStockRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryMovementRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationRepository;
import br.com.hadryan.shosphere.inventory.repository.InventoryReservationCoordinationRepository;
import br.com.hadryan.shosphere.inventory.client.CatalogClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class InventoryServiceApplicationTests {

    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private InventoryStockRepository inventoryStockRepository;
    @MockitoBean private InventoryMovementRepository inventoryMovementRepository;
    @MockitoBean private InventoryReservationRepository inventoryReservationRepository;
    @MockitoBean private InventoryReservationCoordinationRepository inventoryReservationCoordinationRepository;
    @MockitoBean private CatalogClient catalogClient;
    @Autowired private ApplicationContext context;

	@Test
	void contextLoads() {
		assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
		assertTrue(context.getBeansOfType(Flyway.class).isEmpty());
		assertTrue(context.getBeansOfType(EntityManagerFactory.class).isEmpty());
	}

}
