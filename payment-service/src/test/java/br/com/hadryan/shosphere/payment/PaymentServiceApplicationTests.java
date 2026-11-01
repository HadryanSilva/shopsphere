package br.com.hadryan.shosphere.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import br.com.hadryan.shosphere.payment.repository.PaymentRepository;
import br.com.hadryan.shosphere.payment.repository.PaymentMovementRepository;

@SpringBootTest
@ActiveProfiles("test")
class PaymentServiceApplicationTests {

    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private PaymentRepository paymentRepository;
    @MockitoBean private PaymentMovementRepository paymentMovementRepository;

	@Test
	void contextLoads() {
	}

}
