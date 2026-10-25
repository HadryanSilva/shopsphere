package br.com.hadryan.shopsphere.catalog.model;

import br.com.hadryan.shopsphere.catalog.model.enums.ProductStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
        name = "products",
        indexes = {
                @Index(name = "idx_product_name", columnList = "name"),
                @Index(name = "idx_product_category", columnList = "category_id")
        }
)
public class Product extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(length = 2048)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status = ProductStatus.ACTIVE;

    @Version
    private long version;

    protected Product() {

    }

    public Product(String name, String description, UUID categoryId, BigDecimal price, String imageUrl) {
        this.name = name;
        this.description = description;
        this.categoryId = categoryId;
        this.price = price;
        this.imageUrl = imageUrl;
    }

    public void setPrice(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException(
                    "O preço deve ser superior a zero"
            );
        }
        this.price = price;
    }

}
