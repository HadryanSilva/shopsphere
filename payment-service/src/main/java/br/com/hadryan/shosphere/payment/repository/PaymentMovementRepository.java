package br.com.hadryan.shosphere.payment.repository;

import br.com.hadryan.shosphere.payment.model.PaymentMovement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentMovementRepository extends JpaRepository<PaymentMovement, PaymentMovement.Key> {}
