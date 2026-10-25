package br.com.hadryan.shosphere.order.service.impl;

import br.com.hadryan.shosphere.order.mapper.request.ShippingAddressRequest;
import br.com.hadryan.shosphere.order.mapper.response.OrderResponse;
import br.com.hadryan.shosphere.order.model.ShippingAddress;
import br.com.hadryan.shosphere.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderIdempotency {
    private final OrderRepository orderRepository;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<OrderResponse> findExisting(UUID customerId, UUID key, ShippingAddressRequest address) {
        return orderRepository.findByCustomerIdAndIdempotencyKey(customerId, key.toString()).map(order -> {
            if (!sameAddress(order.getShippingAddress(), address)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key belongs to a different checkout request");
            }
            return OrderResponse.fromOrder(order);
        });
    }

    private boolean sameAddress(ShippingAddress saved, ShippingAddressRequest requested) {
        return saved != null && requested != null
                && Objects.equals(saved.getRecipient(), requested.recipient())
                && Objects.equals(saved.getAddressLine(), requested.addressLine())
                && Objects.equals(saved.getAddressLine2(), requested.addressLine2())
                && Objects.equals(saved.getCity(), requested.city())
                && Objects.equals(saved.getPostalCode(), requested.postalCode())
                && Objects.equals(saved.getCountryCode(), requested.countryCode());
    }
}
