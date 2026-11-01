package br.com.hadryan.shosphere.payment.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTests {
    private MockMvc mvc() { return MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new ApiExceptionHandler()).build(); }

    @ParameterizedTest @CsvSource({"notfound,404", "conflict,409", "data,409", "optimistic,409", "locking,409", "upstream,502", "unavailable,503", "unexpected,500"})
    void errorsUseProblemDetailsWithoutInternalDetails(String mode, int status) throws Exception {
        mvc().perform(get("/probe/" + mode)).andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(status)).andExpect(jsonPath("$.instance").value("/probe/" + mode))
                .andExpect(jsonPath("$.errors").isArray()).andExpect(content().string(not(containsString("sensitive"))));
    }
    @Test void invalidBodyExposesFieldNamesWithoutEchoingValues() throws Exception {
        mvc().perform(post("/probe/validate").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("amount"));
        mvc().perform(post("/probe/validate").contentType("application/json").content("{invalid-json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Invalid request."));
    }
    @Test void securityExceptionsRemainOwnedBySecurityHandlers() {
        var advice = new ApiExceptionHandler(); var request = new ServletWebRequest(new MockHttpServletRequest());
        assertThrows(BadCredentialsException.class, () -> advice.unexpectedFailure(new BadCredentialsException("sensitive token"), request));
        assertThrows(AccessDeniedException.class, () -> advice.unexpectedFailure(new AccessDeniedException("sensitive role"), request));
    }
    @Test void unexpectedFailureLogContainsTypeWithoutBodyOrStackTrace() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start(); logger.addAppender(events);
        try { mvc().perform(get("/probe/unexpected")).andExpect(status().isInternalServerError()); }
        finally { logger.detachAppender(events); events.stop(); }
        assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains("IllegalStateException")));
        assertTrue(events.list.stream().noneMatch(event -> event.getFormattedMessage().contains("sensitive") || event.getThrowableProxy() != null));
    }
    record Request(@NotNull Integer amount) {}
    @TestComponent @RestController
    static class Probe {
        @PostMapping("/probe/validate") void validate(@Valid @RequestBody Request request) {}
        @GetMapping("/probe/{mode}") void fail(@PathVariable String mode) {
            switch (mode) {
                case "notfound" -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
                case "conflict" -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment intent conflicts");
                case "data" -> throw new DataIntegrityViolationException("sensitive SQL");
                case "optimistic" -> throw new OptimisticLockingFailureException("sensitive SQL");
                case "locking" -> throw new PessimisticLockingFailureException("sensitive SQL");
                case "upstream" -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "sensitive downstream");
                case "unavailable" -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "sensitive downstream");
                default -> throw new IllegalStateException("sensitive token secret card details");
            }
        }
    }
}
