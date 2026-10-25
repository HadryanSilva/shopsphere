package br.com.hadryan.shopsphere.catalog.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTests {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ErrorProbeController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @ParameterizedTest
    @CsvSource({"missing,404", "conflict,409", "integrity,409", "locking,409", "argument,500",
            "state,500", "upstream,502", "unavailable,503", "internal,500"})
    void standardizesStatusAndDoesNotExposeInternalDetails(String kind, int expected) throws Exception {
        mvc.perform(get("/errors/{kind}", kind))
                .andExpect(status().is(expected))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/errors/" + kind))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(containsString("private-password"))));
    }

    @Test
    void validationIncludesFieldAndMessageWithoutRejectedValue() throws Exception {
        mvc.perform(post("/errors/validation").contentType("application/json").content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
    }

    @Test
    void methodValidationIncludesParameterName() throws Exception {
        mvc.perform(get("/errors/list").param("ids", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ids"));
    }

    @Test
    void malformedJsonDoesNotEchoRequestBody() throws Exception {
        mvc.perform(post("/errors/validation").contentType("application/json").content("{private-password"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(content().string(not(containsString("private-password"))));
    }

    @Test
    void methodNotAllowedRetainsAllowHeader() throws Exception {
        mvc.perform(post("/errors/missing"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.status").value(405));
    }

    @RestController
    @TestComponent
    static class ErrorProbeController {
        @GetMapping("/errors/{kind}")
        void failure(@PathVariable String kind) {
            switch (kind) {
                case "missing" -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
                case "conflict" -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Business conflict");
                case "integrity" -> throw new DataIntegrityViolationException("SQL private-password");
                case "locking" -> throw new OptimisticLockingFailureException("private-password");
                case "argument" -> throw new IllegalArgumentException("private-password");
                case "state" -> throw new IllegalStateException("private-password");
                case "upstream" -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "private-password");
                case "unavailable" -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "private-password");
                default -> throw new RuntimeException("SQL private-password");
            }
        }

        @PostMapping("/errors/validation")
        void validation(@Valid @RequestBody Payload payload) {}

        @GetMapping("/errors/list")
        void list(@NotEmpty @RequestParam List<@NotNull UUID> ids) {}
    }

    record Payload(@NotBlank String name) {}
}

