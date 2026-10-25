package br.com.hadryan.shopsphere.catalog.mapper.request;

import br.com.hadryan.shopsphere.catalog.model.enums.ProductStatus;

import java.math.BigDecimal;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Digits;

public record UpdateProductRequest(
        @Pattern(regexp = "(?s).*\\P{javaWhitespace}.*", message = "must not be blank") @Size(max = 150) String name,
        @Size(max = 2000) String description,
        @Positive @Digits(integer = 17, fraction = 2) BigDecimal price,
        @Size(max = 2048) String imageUrl,
        ProductStatus status
) {
}
