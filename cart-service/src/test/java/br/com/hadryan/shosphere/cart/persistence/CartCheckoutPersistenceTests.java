package br.com.hadryan.shosphere.cart.persistence;

import br.com.hadryan.shosphere.cart.mapper.request.AddProductRequest;
import br.com.hadryan.shosphere.cart.model.Cart;
import br.com.hadryan.shosphere.cart.model.enums.CartCheckoutStatus;
import br.com.hadryan.shosphere.cart.model.enums.CartStatus;
import br.com.hadryan.shosphere.cart.repository.CartRepository;
import br.com.hadryan.shosphere.cart.service.RemoveCartItemUseCase;
import br.com.hadryan.shosphere.cart.service.impl.*;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class CartCheckoutPersistenceTests {
    private DriverManagerDataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private String schema;
    private final UUID customerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID checkoutId = UUID.randomUUID();
    private final String client = "shopsphere-order";

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        schema = "cart_checkout_test_" + UUID.randomUUID().toString().replace("-", "");
        // Exercise a V2 installation with existing cart data before applying V3.
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("2").locations("classpath:db/migration").load().migrate();
        try (var connection = dataSource.getConnection(); var insert = connection.prepareStatement(
                "INSERT INTO " + schema + ".carts(id, customer_id) VALUES (?, ?)")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, UUID.randomUUID());
            insert.executeUpdate();
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        openContext();
    }

    private void openContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean("entityManagerFactory", LocalContainerEntityManagerFactoryBean.class, () -> {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(Cart.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.default_schema", schema,
                    "hibernate.hbm2ddl.auto", "validate", "hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        });
        context.register(PersistenceConfig.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (context != null) context.close();
        if (schema != null) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private CartCheckoutService checkouts() { return context.getBean(CartCheckoutService.class); }
    private UUID add(UUID owner, UUID cartId, int quantity) {
        return context.getBean(AddCartItemTransaction.class).execute(owner,
                new AddProductRequest(cartId, productId, quantity));
    }
    private CartCheckoutSnapshot prepare() { return checkouts().prepare(checkoutId, customerId, client).snapshot(); }

    @Test
    void upgradeRestartAndCompletionPreserveSnapshotAndAllowANewActiveCart() {
        var cartId = add(customerId, null, 2);
        var prepared = prepare();
        assertEquals(cartId, prepared.cartId());
        assertEquals(List.of(new CartCheckoutSnapshot.Item(productId, 2)), prepared.items());
        context.close();
        openContext();
        assertEquals(prepared, checkouts().get(checkoutId, client));
        assertFalse(checkouts().prepare(checkoutId, customerId, client).created());
        var orderId = UUID.randomUUID();
        var complete = checkouts().complete(checkoutId, orderId, client);
        assertEquals(complete, checkouts().complete(checkoutId, orderId, client));
        assertEquals(CartCheckoutStatus.COMPLETED, complete.status());
        assertEquals(CartStatus.CHECKED_OUT, context.getBean(CartRepository.class).findById(cartId).orElseThrow().getStatus());
        assertEquals(2, quantity(cartId));
        assertStatus(409, () -> checkouts().complete(checkoutId, UUID.randomUUID(), client));
        assertStatus(409, () -> checkouts().abort(checkoutId, customerId, client));
        assertStatus(404, () -> add(customerId, cartId, 1));
        var nextCart = add(customerId, null, 3);
        assertNotEquals(cartId, nextCart);
        assertEquals(complete, checkouts().prepare(checkoutId, customerId, client).snapshot());
        assertEquals(3, quantity(nextCart));
    }

    @Test
    void everyMutationIsBlockedButOtherCustomersRemainIndependent() {
        var cartId = add(customerId, null, 2);
        prepare();
        assertStatus(409, () -> add(customerId, null, 1));
        assertStatus(409, () -> add(customerId, cartId, 1));
        assertStatus(409, () -> context.getBean(UpdateCartItemTransaction.class).execute(customerId, productId, 9));
        assertStatus(409, () -> context.getBean(RemoveCartItemUseCase.class).execute(customerId, productId));
        assertStatus(409, () -> context.getBean(RemoveCartItemUseCase.class).execute(customerId, UUID.randomUUID()));
        assertEquals(2, quantity(cartId));
        assertEquals(7, quantity(add(UUID.randomUUID(), null, 7)));
        assertStatus(404, () -> checkouts().get(checkoutId, "another-service"));
        assertStatus(409, () -> checkouts().prepare(checkoutId, UUID.randomUUID(), client));
        assertStatus(409, () -> checkouts().prepare(UUID.randomUUID(), customerId, client));
    }

    @Test
    void abortReleasesOnlyItsClaimAndLatePreparationCannotReacquireIt() {
        var cartId = add(customerId, null, 2);
        var original = prepare();
        var aborted = checkouts().abort(checkoutId, customerId, client);
        assertEquals(original.items(), aborted.items());
        assertEquals(aborted, checkouts().abort(checkoutId, customerId, client));
        assertStatus(409, this::prepare);
        add(customerId, null, 3);
        assertEquals(5, quantity(cartId));
        var replacement = checkouts().prepare(UUID.randomUUID(), customerId, client).snapshot();
        checkouts().abort(checkoutId, customerId, client);
        assertStatus(409, () -> add(customerId, null, 1));
        assertEquals(5, replacement.items().getFirst().quantity());
        assertEquals(2, checkouts().get(checkoutId, client).items().getFirst().quantity());
    }

    @Test
    void earlyAbortSurvivesRestartAndPreventsLatePrepareWithoutTouchingCart() {
        var aborted = checkouts().abort(checkoutId, customerId, client);
        assertNull(aborted.cartId());
        assertTrue(aborted.items().isEmpty());
        context.close();
        openContext();
        var cartId = add(customerId, null, 2);
        assertStatus(409, this::prepare);
        assertStatus(409, () -> checkouts().complete(checkoutId, UUID.randomUUID(), client));
        assertEquals(aborted, checkouts().get(checkoutId, client));
        add(customerId, cartId, 1);
        assertEquals(3, quantity(cartId));
    }

    @Test
    void absentAndEmptyCartsDoNotConsumePreparationIdentity() {
        assertStatus(404, this::prepare);
        var cartId = add(customerId, null, 1);
        context.getBean(RemoveCartItemUseCase.class).execute(customerId, productId);
        assertStatus(409, this::prepare);
        add(customerId, cartId, 1);
        assertTrue(checkouts().prepare(checkoutId, customerId, client).created());
    }

    @Test
    void snapshotIncludesAllItemsWithoutACheckoutSpecificLimit() {
        var cartId = add(customerId, null, Integer.MAX_VALUE);
        var tx = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        tx.executeWithoutResult(status -> {
            var repository = context.getBean(CartRepository.class);
            var cart = repository.findWithLockById(cartId).orElseThrow();
            for (int i = 0; i < 100; i++) cart.addItem(UUID.randomUUID(), i + 1);
            repository.saveAndFlush(cart);
        });
        var snapshot = prepare();
        assertEquals(101, snapshot.items().size());
        assertEquals(Integer.MAX_VALUE, snapshot.items().stream()
                .filter(item -> item.productId().equals(productId)).findFirst().orElseThrow().quantity());
        assertEquals(snapshot, checkouts().get(checkoutId, client));
    }

    @Test
    void concurrentReplayCreatesOnlyOnePreparation() throws Exception {
        add(customerId, null, 2);
        var results = race(() -> checkouts().prepare(checkoutId, customerId, client),
                () -> checkouts().prepare(checkoutId, customerId, client));
        var first = (CartCheckoutTransaction.Preparation) results.getFirst();
        var second = (CartCheckoutTransaction.Preparation) results.getLast();
        assertEquals(first.snapshot(), second.snapshot());
        assertNotEquals(first.created(), second.created());
    }

    @Test
    void differentIntentionsCannotBothClaimTheSameCart() throws Exception {
        add(customerId, null, 2);
        var results = race(() -> statusOrSuccess(() -> checkouts().prepare(checkoutId, customerId, client)),
                () -> statusOrSuccess(() -> checkouts().prepare(UUID.randomUUID(), customerId, client)));
        assertTrue(results.contains(200));
        assertTrue(results.contains(409));
    }

    @Test
    void concurrentEditIsEitherIncludedOrRejected() throws Exception {
        var cartId = add(customerId, null, 2);
        var results = race(this::prepare, () -> statusOrSuccess(() -> add(customerId, null, 3)));
        var snapshot = (CartCheckoutSnapshot) results.getFirst();
        var editStatus = (Integer) results.getLast();
        assertEquals(editStatus == 200 ? 5 : 2, snapshot.items().getFirst().quantity());
        assertTrue(editStatus == 200 || editStatus == 409);
        assertEquals(snapshot.items().getFirst().quantity(), quantity(cartId));
    }

    @Test
    void concurrentAbortAndPreparationAlwaysConvergeToAborted() throws Exception {
        var cartId = add(customerId, null, 2);
        race(() -> statusOrSuccess(this::prepare), () -> checkouts().abort(checkoutId, customerId, client));
        assertEquals(CartCheckoutStatus.ABORTED, checkouts().get(checkoutId, client).status());
        assertStatus(409, this::prepare);
        add(customerId, null, 1);
        assertEquals(3, quantity(cartId));
    }

    @Test
    void completionAndAbortAreAtomicUnderContention() throws Exception {
        var cartId = add(customerId, null, 2);
        prepare();
        var results = race(() -> statusOrSuccess(() -> checkouts().complete(checkoutId, UUID.randomUUID(), client)),
                () -> statusOrSuccess(() -> checkouts().abort(checkoutId, customerId, client)));
        assertTrue(results.contains(200));
        assertTrue(results.contains(409));
        var snapshot = checkouts().get(checkoutId, client);
        var cart = context.getBean(CartRepository.class).findById(cartId).orElseThrow();
        assertEquals(snapshot.status() == CartCheckoutStatus.COMPLETED ? CartStatus.CHECKED_OUT : CartStatus.ACTIVE,
                cart.getStatus());
        assertEquals(2, quantity(cartId));
    }

    @Test
    void rollbackDoesNotLeaveClaimOrCheckedOutCart() {
        var cartId = add(customerId, null, 2);
        // Force a late SQL failure after the cart status has been flushed.
        prepare();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + schema + ".cart_checkouts ADD CONSTRAINT test_reject_completion CHECK (status <> 'COMPLETED')");
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> checkouts().complete(checkoutId, UUID.randomUUID(), client));
        assertEquals(CartStatus.ACTIVE, context.getBean(CartRepository.class).findById(cartId).orElseThrow().getStatus());
        assertEquals(CartCheckoutStatus.PREPARED, checkouts().get(checkoutId, client).status());
    }

    @Test
    void databaseRejectsInvalidStateDuplicateClaimsAndInvalidItems() throws Exception {
        var cartId = add(customerId, null, 2);
        prepare();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            var checkout = "'" + checkoutId + "'";
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE " + schema
                    + ".cart_checkouts SET status = 'COMPLETED' WHERE checkout_id = " + checkout));
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO " + schema
                    + ".cart_checkouts(checkout_id, customer_id, service_client, cart_id, status, prepared_at) VALUES ('"
                    + UUID.randomUUID() + "','" + customerId + "','shopsphere-order','" + cartId + "','PREPARED',CURRENT_TIMESTAMP)"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO " + schema
                    + ".cart_checkout_items VALUES (" + checkout + ",'" + UUID.randomUUID() + "',0)"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO " + schema
                    + ".cart_checkout_items VALUES (" + checkout + ",'" + productId + "',1)"));
        }
    }

    @Test
    void editWaitingBehindPreparationSeesTheCommittedClaim() throws Exception {
        var cartId = add(customerId, null, 2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tx = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var preparing = executor.submit(() -> tx.execute(status -> {
                context.getBean(CartRepository.class).findWithLockById(cartId).orElseThrow();
                // Use the repository within this transaction so the claim stays uncommitted.
                var cart = context.getBean(CartRepository.class).findById(cartId).orElseThrow();
                var entity = br.com.hadryan.shosphere.cart.model.CartCheckout.prepare(checkoutId, customerId, client, cart);
                context.getBean(br.com.hadryan.shosphere.cart.repository.CartCheckoutRepository.class).saveAndFlush(entity);
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return true;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var editing = executor.submit(() -> statusOrSuccess(() -> add(customerId, null, 3)));
            release.countDown();
            assertTrue(preparing.get(15, TimeUnit.SECONDS));
            assertEquals(409, editing.get(15, TimeUnit.SECONDS));
            assertEquals(2, quantity(cartId));
        } finally { release.countDown(); }
    }

    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
                return (Object) action.call();
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.getFirst().get(20, TimeUnit.SECONDS), futures.getLast().get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); }
    }

    private int statusOrSuccess(Callable<?> action) throws Exception {
        try { action.call(); return 200; }
        catch (ResponseStatusException failure) { return failure.getStatusCode().value(); }
    }

    private void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }

    private int quantity(UUID cartId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT quantity FROM " + schema + ".cart_items WHERE cart_id = ? AND product_id = ?")) {
            statement.setObject(1, cartId);
            statement.setObject(2, productId);
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getInt(1); }
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CartRepository.class)
    @Import({CartCheckoutService.class, CartCheckoutTransaction.class, AddCartItemTransaction.class,
            UpdateCartItemTransaction.class, RemoveCartItemUseCaseImpl.class})
    static class PersistenceConfig {
        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }
}
