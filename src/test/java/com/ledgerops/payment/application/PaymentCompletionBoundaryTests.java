package com.ledgerops.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.ledgerops.ledger.application.PaymentSuccessLedgerService;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentCompletionBoundaryTests {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void paymentSuccessCompletionHasNoPublicHttpEndpoint() {
        boolean publicCompletionEndpoint = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .map(String::toLowerCase)
                .anyMatch(pattern -> pattern.contains("complete")
                        || pattern.contains("provider-success"));

        assertFalse(publicCompletionEndpoint);
    }

    @Test
    void paymentOwnsTheTransactionAndLedgerMustJoinIt() throws Exception {
        Method paymentMethod = CompletePaymentAfterProviderSuccess.class.getMethod(
                "complete",
                UUID.class,
                PaymentId.class
        );
        Transactional paymentTransaction = paymentMethod.getAnnotation(Transactional.class);
        assertNotNull(paymentTransaction);
        assertEquals(Propagation.REQUIRED, paymentTransaction.propagation());

        Method findMethod = PaymentSuccessLedgerService.class.getMethod(
                "findByPaymentSource",
                UUID.class,
                UUID.class
        );
        Method postMethod = PaymentSuccessLedgerService.class.getMethod(
                "postPaymentSuccess",
                com.ledgerops.ledger.api.PaymentSuccessPostingRequest.class
        );
        assertEquals(
                Propagation.MANDATORY,
                findMethod.getAnnotation(Transactional.class).propagation()
        );
        assertEquals(
                Propagation.MANDATORY,
                postMethod.getAnnotation(Transactional.class).propagation()
        );
    }
}
