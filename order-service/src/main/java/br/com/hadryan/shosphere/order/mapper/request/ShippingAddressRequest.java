package br.com.hadryan.shosphere.order.mapper.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShippingAddressRequest(
        @NotBlank @Size(max = 150) String recipient,
        @NotBlank @Size(max = 200) String addressLine,
        @Size(max = 200) String addressLine2,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode
) {
}
