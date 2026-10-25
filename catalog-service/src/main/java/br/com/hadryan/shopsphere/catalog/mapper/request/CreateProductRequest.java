package br.com.hadryan.shopsphere.catalog.mapper.request;

import br.com.hadryan.shopsphere.catalog.model.Product;
import br.com.hadryan.shopsphere.catalog.model.enums.Currency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateProductRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 2000) String description,
        @NotNull UUID categoryId,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal price,
        @NotNull Currency currency,
        @Size(max = 2048) String imageUrl
)
{
    public Product toProduct() {
        return new Product(
                this.name(),
                this.description(),
                this.categoryId(),
                this.price(),
                this.imageUrl()
        );
    }
}
