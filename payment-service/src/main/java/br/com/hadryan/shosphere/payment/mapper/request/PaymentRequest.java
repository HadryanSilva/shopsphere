package br.com.hadryan.shosphere.payment.mapper.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record PaymentRequest(@NotNull @Positive BigDecimal amount, @NotNull @Pattern(regexp = "EUR") String currency) {
    public BigDecimal normalizedAmount() {
        if (amount != null && "EUR".equals(currency) && amount.compareTo(new BigDecimal("0.01")) >= 0
                && (long) amount.precision() - amount.scale() <= 17) {
            try {
                var normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
                if (normalized.precision() <= 19) return normalized;
            } catch (ArithmeticException ignored) { }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive exact EUR amount within NUMERIC(19,2) is required");
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported payment field");
    }
}
