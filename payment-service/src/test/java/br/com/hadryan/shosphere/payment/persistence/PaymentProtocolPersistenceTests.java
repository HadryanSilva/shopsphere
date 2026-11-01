package br.com.hadryan.shosphere.payment.persistence;

import br.com.hadryan.shosphere.payment.PaymentServiceApplication;
import br.com.hadryan.shosphere.payment.mapper.request.PaymentRequest;
import br.com.hadryan.shosphere.payment.mapper.response.PaymentResponse;
import br.com.hadryan.shosphere.payment.model.PaymentStatus;
import br.com.hadryan.shosphere.payment.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_POSTGRES_URL", matches = ".+")
class PaymentProtocolPersistenceTests {
    private static final String CLIENT = "shopsphere-order";
    private static final PaymentRequest TERMS = new PaymentRequest(new BigDecimal("25.00"), "EUR");
    private static final String BODY = "{\"amount\":25.00,\"currency\":\"EUR\"}";
    private final UUID id = UUID.randomUUID();
    private String schema;
    private JdbcTemplate jdbc;
    private WebApplicationContextRunner runner;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(System.getenv("SHOPSPHERE_TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("SHOPSPHERE_TEST_POSTGRES_PASSWORD", "postgres"));
        jdbc = new JdbcTemplate(source);
        schema = "payment_protocol_test_" + UUID.randomUUID().toString().replace("-", "");
        var issuer = System.getenv().getOrDefault("SHOPSPHERE_TEST_KEYCLOAK_ISSUER", "http://localhost:8180/realms/shopsphere");
        runner = new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PaymentServiceApplication.class)
                .withBean(PaymentStartupPersistenceTests.TestFilter.class, PaymentStartupPersistenceTests.TestFilter::new)
                .withBean(DataSource.class, () -> source)
                .withPropertyValues("spring.profiles.active=default", "spring.flyway.schemas=" + schema,
                        "spring.flyway.default-schema=" + schema, "spring.jpa.properties.hibernate.default_schema=" + schema,
                        "payment.simulator.enabled=true", "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuer,
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + issuer + "/protocol/openid-connect/certs");
    }
    @AfterEach void cleanup() {
        if (schema == null || !schema.matches("payment_protocol_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test schema");
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
    private MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    private String path() { return "/internal/api/v1/payments/" + id; }
    private org.springframework.test.web.servlet.request.RequestPostProcessor service() {
        return jwt().jwt(token -> token.claim("azp", CLIENT)).authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_SERVICE"));
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + schema + "." + table, Long.class); }
    private String storedStatus() { return jdbc.queryForObject("SELECT status FROM " + schema + ".payments WHERE order_id=?", String.class, id); }
    private void conflict(Supplier<?> operation, int status) {
        assertEquals(status, assertThrows(ResponseStatusException.class, operation::get).getStatusCode().value());
    }

    @Test void httpCaptureReplayReadAndTotalRefundHaveStableRepresentationsAndLocations() {
        runner.run(context -> {
            assertNull(context.getStartupFailure()); var mvc = mvc(context.getSourceApplicationContext());
            var first = mvc.perform(put(path()).with(service()).contentType("application/json").content(BODY))
                    .andExpect(status().isCreated()).andExpect(header().string("Location", path()))
                    .andExpect(jsonPath("$.orderId").value(id.toString())).andExpect(jsonPath("$.status").value("CAPTURED"))
                    .andExpect(jsonPath("$.amount").value(25)).andExpect(jsonPath("$.currency").value("EUR"))
                    .andExpect(jsonPath("$.capturedAt").isString()).andExpect(jsonPath("$.refundedAt").value(nullValue()))
                    .andReturn().getResponse().getContentAsString();
            mvc.perform(put(path()).with(service()).contentType("application/json").content("{\"amount\":25.000,\"currency\":\"EUR\"}"))
                    .andExpect(status().isOk()).andExpect(content().json(first));
            mvc.perform(get(path()).with(service())).andExpect(status().isOk()).andExpect(content().json(first));
            for (var method : List.of("PUT", "POST"))
                mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path() + (method.equals("POST") ? "/cancel" : ""))
                        .with(service()).contentType("application/json").content("{\"amount\":26,\"currency\":\"EUR\"}"))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.errors").isArray());
            var refund = mvc.perform(post(path() + "/cancel").with(service()).contentType("application/json").content(BODY))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"))
                    .andExpect(jsonPath("$.refundedAt").isString()).andReturn().getResponse().getContentAsString();
            mvc.perform(post(path() + "/cancel").with(service()).contentType("application/json").content(BODY))
                    .andExpect(status().isOk()).andExpect(content().json(refund));
            mvc.perform(put(path()).with(service()).contentType("application/json").content(BODY)).andExpect(status().isConflict());
            assertEquals(2, count("payment_movements"));
            assertEquals(List.of(new BigDecimal("25.00"), new BigDecimal("25.00")), jdbc.queryForList(
                    "SELECT amount FROM " + schema + ".payment_movements ORDER BY kind", BigDecimal.class));
        });
    }

    @Test void cancelBeforeFirstPutCreatesDurableBarrierAndNeverCaptures() {
        runner.run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(get(path()).with(service())).andExpect(status().isNotFound());
            var barrier = mvc.perform(post(path() + "/cancel").with(service()).contentType("application/json").content(BODY))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.capturedAt").value(nullValue())).andExpect(jsonPath("$.refundedAt").value(nullValue()))
                    .andReturn().getResponse().getContentAsString();
            mvc.perform(post(path() + "/cancel").with(service()).contentType("application/json").content(BODY))
                    .andExpect(status().isOk()).andExpect(content().json(barrier));
            mvc.perform(put(path()).with(service()).contentType("application/json").content(BODY)).andExpect(status().isConflict());
            assertEquals(1, count("payments")); assertEquals(0, count("payment_movements"));
        });
    }

    @Test void invalidBodiesAreBadRequestOnBothWritesWithoutPersistingAnything() {
        runner.run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            for (var body : List.of("", "null", "[]", "{broken", "{}", "{\"amount\":25}", "{\"currency\":\"EUR\"}",
                    "{\"amount\":null,\"currency\":\"EUR\"}", "{\"amount\":0,\"currency\":\"EUR\"}",
                    "{\"amount\":-1,\"currency\":\"EUR\"}", "{\"amount\":25.001,\"currency\":\"EUR\"}",
                    "{\"amount\":100000000000000000,\"currency\":\"EUR\"}", "{\"amount\":1,\"currency\":\"USD\"}",
                    "{\"amount\":1,\"currency\":\"eur\"}", "{\"amount\":\"NaN\",\"currency\":\"EUR\"}",
                    "{\"amount\":1e1000,\"currency\":\"EUR\"}", "{\"amount\":1e-1000,\"currency\":\"EUR\"}",
                    "{\"amount\":25,\"currency\":\"EUR\",\"customerId\":\"other\"}",
                    "{\"amount\":25,\"currency\":\"EUR\",\"status\":\"CAPTURED\"}",
                    "{\"amount\":25,\"currency\":\"EUR\",\"card\":\"forbidden\"}")) {
                for (var method : List.of("PUT", "POST"))
                    mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path() + (method.equals("POST") ? "/cancel" : ""))
                            .with(service()).contentType("application/json").content(body))
                            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                            .andExpect(jsonPath("$.errors").isArray());
            }
            mvc.perform(put("/internal/api/v1/payments/not-a-uuid").with(service()).contentType("application/json").content(BODY))
                    .andExpect(status().isBadRequest());
            assertEquals(0, count("payments")); assertEquals(0, count("payment_movements"));
        });
    }

    @Test void incompatibleTermsConflictAndOtherClientIsHiddenOnEveryOperation() {
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            var captured = payments.capture(CLIENT, id, TERMS).payment();
            var otherTerms = new PaymentRequest(new BigDecimal("26"), "EUR");
            conflict(() -> payments.capture(CLIENT, id, otherTerms), 409);
            conflict(() -> payments.cancel(CLIENT, id, otherTerms), 409);
            conflict(() -> payments.get("other-client", id), 404);
            conflict(() -> payments.capture("other-client", id, TERMS), 404);
            conflict(() -> payments.cancel("other-client", id, TERMS), 404);
            assertEquals(captured, payments.get(CLIENT, id)); assertEquals(1, count("payment_movements"));
        });
    }

    @Test void exactMonetaryLimitsAreAcceptedWithoutRoundingOrLoss() {
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            for (var amount : List.of("0.01", "99999999999999999.99", "25.00000", "1E+2")) {
                var order = UUID.randomUUID();
                var result = payments.capture(CLIENT, order, new PaymentRequest(new BigDecimal(amount), "EUR"));
                assertEquals(new BigDecimal(amount).setScale(2), result.payment().amount());
                assertEquals(result.payment(), payments.get(CLIENT, order));
            }
            assertEquals(4, count("payments")); assertEquals(4, count("payment_movements"));
        });
    }

    @Test void declinedIsSuccessfulTerminalOutcomeWithoutMovementOrRecaptureAfterConfigurationChanges() {
        runner.withPropertyValues("payment.simulator.capture-result=DECLINED").run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(put(path()).with(service()).contentType("application/json").content(BODY)).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DECLINED")).andExpect(jsonPath("$.capturedAt").value(nullValue()));
        });
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            assertFalse(payments.capture(CLIENT, id, TERMS).created());
            assertEquals(PaymentStatus.DECLINED, payments.capture(CLIENT, id, TERMS).payment().status());
            assertEquals(PaymentStatus.DECLINED, payments.cancel(CLIENT, id, TERMS).status());
            assertEquals(0, count("payment_movements"));
        });
    }

    @Test void processingReturns202AndGetDoesNotProgressUntilReplayAfterRestart() {
        runner.withPropertyValues("payment.simulator.capture-result=PENDING").run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            var body = mvc.perform(put(path()).with(service()).contentType("application/json").content(BODY))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PROCESSING"))
                    .andExpect(jsonPath("$.capturedAt").value(nullValue())).andReturn().getResponse().getContentAsString();
            mvc.perform(get(path()).with(service())).andExpect(status().isOk()).andExpect(content().json(body));
            assertEquals(0, count("payment_movements"));
        });
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            assertEquals(PaymentStatus.PROCESSING, payments.get(CLIENT, id).status());
            var result = payments.capture(CLIENT, id, TERMS);
            assertFalse(result.created()); assertEquals(PaymentStatus.CAPTURED, result.payment().status());
            assertEquals(1, count("payment_movements"));
        });
    }

    @Test void refundDecisionReturns202AndSurvivesRestartBeforeFullRefund() {
        runner.withPropertyValues("payment.simulator.refund-result=PENDING").run(context -> {
            var payments = context.getBean(PaymentService.class); payments.capture(CLIENT, id, TERMS);
            var mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(post(path() + "/cancel").with(service()).contentType("application/json").content(BODY))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("REFUND_PENDING"))
                    .andExpect(jsonPath("$.capturedAt").isString()).andExpect(jsonPath("$.refundedAt").value(nullValue()));
            conflict(() -> payments.capture(CLIENT, id, TERMS), 409);
            assertEquals(1, count("payment_movements"));
        });
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            assertEquals(PaymentStatus.REFUND_PENDING, payments.get(CLIENT, id).status());
            assertEquals(PaymentStatus.REFUNDED, payments.cancel(CLIENT, id, TERMS).status());
            assertEquals(2, count("payment_movements"));
        });
    }

    @Test void captureUnavailableIs503AndPreservesIntentForReplayAfterRestart() {
        runner.withPropertyValues("payment.simulator.capture-result=UNAVAILABLE").run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(put(path()).with(service()).contentType("application/json").content(BODY)).andExpect(status().isServiceUnavailable());
            assertEquals("PROCESSING", storedStatus()); assertEquals(0, count("payment_movements"));
        });
        runner.run(context -> {
            assertEquals(PaymentStatus.CAPTURED, context.getBean(PaymentService.class).capture(CLIENT, id, TERMS).payment().status());
            assertEquals(1, count("payment_movements"));
        });
    }

    @Test void refundUnavailableIs503AndNeverErasesCaptureOrCancellationDecision() {
        runner.withPropertyValues("payment.simulator.refund-result=UNAVAILABLE").run(context -> {
            var payments = context.getBean(PaymentService.class); payments.capture(CLIENT, id, TERMS);
            conflict(() -> payments.cancel(CLIENT, id, TERMS), 503);
            assertEquals("REFUND_PENDING", storedStatus()); assertEquals(1, count("payment_movements"));
            conflict(() -> payments.capture(CLIENT, id, TERMS), 409);
        });
        runner.run(context -> {
            assertEquals(PaymentStatus.REFUNDED, context.getBean(PaymentService.class).cancel(CLIENT, id, TERMS).status());
            assertEquals(2, count("payment_movements"));
        });
    }

    @Test void disabledSimulatorFailsClosedWithoutPretendingToDeclineOrCapture() {
        runner.withPropertyValues("payment.simulator.enabled=false").run(context -> {
            var payments = context.getBean(PaymentService.class);
            conflict(() -> payments.capture(CLIENT, id, TERMS), 503);
            assertEquals("PROCESSING", storedStatus()); assertEquals(0, count("payment_movements"));
            assertEquals(PaymentStatus.CANCELLED, payments.cancel(CLIENT, id, TERMS).status());
        });
    }

    @Test void concurrentCapturesProduceOneIntentionAndOneMovement() {
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            var results = race(8, () -> payments.capture(CLIENT, id, TERMS));
            assertEquals(1, results.stream().filter(PaymentService.Capture::created).count());
            assertTrue(results.stream().allMatch(row -> row.payment().equals(results.getFirst().payment())));
            assertEquals(1, count("payments")); assertEquals(1, count("payment_movements"));
        });
    }

    @Test void concurrentRefundsProduceOneFullRefundAndStableReplay() {
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class); payments.capture(CLIENT, id, TERMS);
            var results = race(8, () -> payments.cancel(CLIENT, id, TERMS));
            assertTrue(results.stream().allMatch(row -> row.status() == PaymentStatus.REFUNDED));
            assertTrue(results.stream().allMatch(row -> row.equals(results.getFirst())));
            assertEquals(2, count("payment_movements"));
        });
    }

    @Test void concurrentInitialCancelsProduceBarrierWithoutCapture() {
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            var results = race(8, () -> payments.cancel(CLIENT, id, TERMS));
            assertTrue(results.stream().allMatch(row -> row.status() == PaymentStatus.CANCELLED));
            conflict(() -> payments.capture(CLIENT, id, TERMS), 409);
            assertEquals(1, count("payments")); assertEquals(0, count("payment_movements"));
        });
    }

    @Test void cancelBetweenRegistrationAndDelayedCapturePreventsFinancialEffect() {
        runner.run(context -> {
            var tx = context.getBean(PaymentTransactions.class);
            assertTrue(tx.register(CLIENT, id, TERMS, false).created());
            assertEquals(PaymentStatus.CANCELLED, context.getBean(PaymentService.class).cancel(CLIENT, id, TERMS).status());
            conflict(() -> tx.capture(CLIENT, id, TERMS), 409);
            assertEquals(0, count("payment_movements"));
        });
    }

    @Test void captureHoldingRootLockWinsAndConcurrentCancelRefundsExactlyOnce() {
        var simulator = mock(PaymentSimulator.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(simulator.capture()).thenAnswer(call -> {
            assertEquals("PROCESSING", storedStatus()); entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS)); return PaymentSimulator.CaptureResult.CAPTURED;
        });
        when(simulator.refund()).thenReturn(PaymentSimulator.RefundResult.REFUNDED);
        runner.withBean("controlledSimulator", PaymentSimulator.class, () -> simulator, bean -> bean.setPrimary(true)).run(context -> {
            var payments = context.getBean(PaymentService.class);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var capture = executor.submit(() -> payments.capture(CLIENT, id, TERMS));
                assertTrue(entered.await(15, TimeUnit.SECONDS));
                var cancel = executor.submit(() -> payments.cancel(CLIENT, id, TERMS));
                try { assertThrows(TimeoutException.class, () -> cancel.get(150, TimeUnit.MILLISECONDS)); }
                finally { release.countDown(); }
                assertEquals(PaymentStatus.CAPTURED, capture.get(15, TimeUnit.SECONDS).payment().status());
                assertEquals(PaymentStatus.REFUNDED, cancel.get(15, TimeUnit.SECONDS).status());
            } finally { release.countDown(); }
            assertEquals(2, count("payment_movements")); verify(simulator, times(1)).capture(); verify(simulator, times(1)).refund();
        });
    }

    @Test void racingFirstPutAndCancelNeverLeaveUncompensatedMoneyOrPermitLatePut() {
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var start = new CountDownLatch(1);
                var capture = executor.submit(() -> { start.await();
                    try { return payments.capture(CLIENT, id, TERMS); }
                    catch (ResponseStatusException exception) { assertEquals(409, exception.getStatusCode().value()); return null; }
                });
                var cancel = executor.submit(() -> { start.await(); return payments.cancel(CLIENT, id, TERMS); });
                start.countDown(); capture.get(15, TimeUnit.SECONDS); var result = cancel.get(15, TimeUnit.SECONDS);
                assertTrue(Set.of(PaymentStatus.CANCELLED, PaymentStatus.REFUNDED).contains(result.status()));
                assertEquals(result, payments.get(CLIENT, id));
                assertEquals(result.status() == PaymentStatus.REFUNDED ? 2 : 0, count("payment_movements"));
                conflict(() -> payments.capture(CLIENT, id, TERMS), 409);
            }
        });
    }

    @Test void racingDifferentAmountsOnlyEstablishesOneImmutableIntent() {
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            var sequence = new java.util.concurrent.atomic.AtomicInteger();
            var results = race(8, () -> {
                var terms = new PaymentRequest(new BigDecimal(25 + sequence.getAndIncrement()), "EUR");
                try { return payments.capture(CLIENT, id, terms).payment(); }
                catch (ResponseStatusException exception) { assertEquals(409, exception.getStatusCode().value()); return null; }
            });
            assertEquals(1, results.stream().filter(Objects::nonNull).count());
            assertEquals(1, count("payments")); assertEquals(1, count("payment_movements"));
        });
    }

    @Test void captureResultFailureRollsBackMovementButKeepsCommittedIntention() {
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class); failStatus("CAPTURED");
            assertThrows(org.springframework.dao.DataAccessException.class, () -> payments.capture(CLIENT, id, TERMS));
            assertEquals("PROCESSING", storedStatus()); assertEquals(0, count("payment_movements"));
            jdbc.execute("DROP TRIGGER fail_result ON " + schema + ".payments");
            assertEquals(PaymentStatus.CAPTURED, payments.capture(CLIENT, id, TERMS).payment().status());
            assertEquals(1, count("payment_movements"));
        });
    }

    @Test void refundResultFailureRollsBackOnlyRefundAndKeepsDurableDecision() {
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class); payments.capture(CLIENT, id, TERMS); failStatus("REFUNDED");
            assertThrows(org.springframework.dao.DataAccessException.class, () -> payments.cancel(CLIENT, id, TERMS));
            assertEquals("REFUND_PENDING", storedStatus()); assertEquals(1, count("payment_movements"));
            jdbc.execute("DROP TRIGGER fail_result ON " + schema + ".payments");
            assertEquals(PaymentStatus.REFUNDED, payments.cancel(CLIENT, id, TERMS).status());
            assertEquals(2, count("payment_movements"));
        });
    }

    @Test void lostResponsesAndRestartReplaySameCaptureAndRefundWithoutNewEffects() {
        var captured = new java.util.concurrent.atomic.AtomicReference<PaymentResponse>();
        var refunded = new java.util.concurrent.atomic.AtomicReference<PaymentResponse>();
        runner.run(context -> captured.set(context.getBean(PaymentService.class).capture(CLIENT, id, TERMS).payment()));
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            assertEquals(captured.get(), payments.get(CLIENT, id));
            assertEquals(captured.get(), payments.capture(CLIENT, id, TERMS).payment());
            refunded.set(payments.cancel(CLIENT, id, TERMS));
        });
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            assertEquals(refunded.get(), payments.get(CLIENT, id)); assertEquals(refunded.get(), payments.cancel(CLIENT, id, TERMS));
            conflict(() -> payments.capture(CLIENT, id, TERMS), 409);
            assertEquals(2, count("payment_movements"));
        });
    }

    @Test void barrierSurvivesRestartWithoutAnyCaptureAttempt() {
        runner.run(context -> context.getBean(PaymentService.class).cancel(CLIENT, id, TERMS));
        runner.run(context -> {
            var payments = context.getBean(PaymentService.class);
            assertEquals(PaymentStatus.CANCELLED, payments.get(CLIENT, id).status());
            conflict(() -> payments.capture(CLIENT, id, TERMS), 409); assertEquals(0, count("payment_movements"));
        });
    }

    @Test void actualControllerRejectsUnauthorizedRolesClientsAndPathsWithoutMutation() {
        runner.run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(put(path()).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
            for (var role : List.of("ADMIN", "CUSTOMER", "INVENTORY_SERVICE"))
                mvc.perform(put(path()).with(jwt().jwt(token -> token.claim("azp", CLIENT)).authorities(new SimpleGrantedAuthority("ROLE_" + role)))
                        .contentType("application/json").content(BODY)).andExpect(status().isForbidden());
            mvc.perform(put(path()).with(jwt().jwt(token -> token.claim("azp", "shopsphere-web"))
                            .authorities(new SimpleGrantedAuthority("ROLE_PAYMENT_SERVICE"))).contentType("application/json").content(BODY))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/payments/" + id).with(service()).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
            assertEquals(0, count("payments"));
            context.getBean(PaymentTransactions.class).register("other-client", id, TERMS, false);
            mvc.perform(get(path()).with(service())).andExpect(status().isNotFound());
            mvc.perform(put(path()).with(service()).contentType("application/json").content(BODY)).andExpect(status().isNotFound());
            mvc.perform(post(path() + "/cancel").with(service()).contentType("application/json").content(BODY)).andExpect(status().isNotFound());
            assertEquals("PROCESSING", storedStatus()); assertEquals(0, count("payment_movements"));
        });
    }

    @Test @EnabledIfEnvironmentVariable(named = "SHOPSPHERE_TEST_PAYMENT_SERVICE_TOKEN", matches = ".+")
    void realKeycloakTokenCapturesAndRefundsThroughActualControllerAndPostgres() {
        runner.run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            var bearer = "Bearer " + System.getenv("SHOPSPHERE_TEST_PAYMENT_SERVICE_TOKEN");
            mvc.perform(put(path()).header("Authorization", bearer).contentType("application/json").content(BODY))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CAPTURED"));
            mvc.perform(get(path()).header("Authorization", bearer)).andExpect(status().isOk());
            mvc.perform(post(path() + "/cancel").header("Authorization", bearer).contentType("application/json").content(BODY))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
            assertEquals(2, count("payment_movements"));
        });
    }

    private void failStatus(String status) {
        jdbc.execute("CREATE FUNCTION " + schema + ".fail_result() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.status='" + status + "' THEN RAISE EXCEPTION 'test result failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_result BEFORE UPDATE ON " + schema + ".payments FOR EACH ROW EXECUTE FUNCTION " + schema + ".fail_result()");
    }
    private <T> List<T> race(int size, Callable<T> work) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < size; i++) futures.add(executor.submit(() -> { start.await(); return work.call(); }));
            start.countDown(); var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
