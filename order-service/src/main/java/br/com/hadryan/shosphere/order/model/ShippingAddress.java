package br.com.hadryan.shosphere.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;


@Getter
@Embeddable
public class ShippingAddress {

    @Column(name = "shipping_recipient",
            length = 150)
    private String recipient;

    @Column(name = "shipping_address_line",
            length = 200)
    private String addressLine;

    @Column(name = "shipping_address_line_2", length = 200)
    private String addressLine2;

    @Column(name = "shipping_city", length = 100)
    private String city;

    @Column(name = "shipping_postal_code",
            length = 20)
    private String postalCode;

    @Column(name = "shipping_country_code",
            length = 2)
    private String countryCode;

    protected ShippingAddress() {}

    public ShippingAddress(
            String recipient,
            String addressLine,
            String city,
            String postalCode,
            String countryCode) {
        this(recipient, addressLine, null, city, postalCode, countryCode);
    }

    public ShippingAddress(
            String recipient,
            String addressLine,
            String addressLine2,
            String city,
            String postalCode,
            String countryCode) {
        this.recipient = recipient;
        this.addressLine = addressLine;
        this.addressLine2 = addressLine2;
        this.city = city;
        this.postalCode = postalCode;
        this.countryCode = countryCode;
    }
}

