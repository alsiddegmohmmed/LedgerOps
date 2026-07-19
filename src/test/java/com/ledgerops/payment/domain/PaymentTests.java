package com.ledgerops.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.merchant.api.MerchantReference;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

class PaymentTests {

    private static final UUID TENANT_ID = UUID.fromString(
            "9d492ac1-cf66-41cc-a82d-96f8a6d22e93"
    );
    private static final UUID MERCHANT_ID = UUID.fromString(
            "94f012db-f9b8-40e8-8188-f9625fca531a"
    );

    @Test
    void createsPaymentWithRequiredFieldsAndCreatedStatus() {
        PaymentId paymentId = PaymentId.from(
                UUID.fromString("32142591-bca0-4907-884a-fefcc2fa86ae")
        );
        MerchantReference merchantReference = MerchantReference.from(
                TENANT_ID,
                MERCHANT_ID
        );
        CustomerId customerId = CustomerId.from(
                UUID.fromString("fe389759-5cbc-490a-a355-835c7082dca2")
        );
        Money amount = Money.of(
                new BigDecimal("125.00"),
                Currency.getInstance("SAR")
        );
        PaymentMethodCategory methodCategory = PaymentMethodCategory.from("card");
        IdempotencyKey idempotencyKey = IdempotencyKey.from("payment-request-1001");

        Payment payment = Payment.create(
                paymentId,
                merchantReference,
                customerId,
                amount,
                methodCategory,
                idempotencyKey
        );

        assertEquals(paymentId, payment.id());
        assertEquals(TENANT_ID, payment.tenantId());
        assertEquals(merchantReference, payment.merchantReference());
        assertEquals(customerId, payment.customerId());
        assertEquals(amount, payment.amount());
        assertEquals(methodCategory, payment.paymentMethodCategory());
        assertEquals(idempotencyKey, payment.idempotencyKey());
        assertEquals(PaymentStatus.CREATED, payment.status());
    }

    @Test
    void rejectsZeroPaymentAmount() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> paymentWithAmount("0.00")
        );

        assertEquals("Payment amount must be positive", exception.getMessage());
    }

    @Test
    void rejectsMissingRequiredRelationship() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Payment.create(
                        PaymentId.newId(),
                        null,
                        customerId(),
                        sar("10.00"),
                        PaymentMethodCategory.from("card"),
                        IdempotencyKey.from("payment-request-1001")
                )
        );

        assertEquals("Merchant reference must not be null", exception.getMessage());
    }

    @Test
    void normalizesTextValueObjectsWithoutInventingCategoryValues() {
        assertEquals("payment-request-1001", IdempotencyKey.from(" payment-request-1001 ").value());
        assertEquals("card", PaymentMethodCategory.from(" card ").value());
    }

    @Test
    void rejectsBlankTextValueObjects() {
        IllegalArgumentException keyFailure = assertThrows(
                IllegalArgumentException.class,
                () -> IdempotencyKey.from("   ")
        );
        IllegalArgumentException categoryFailure = assertThrows(
                IllegalArgumentException.class,
                () -> PaymentMethodCategory.from("   ")
        );

        assertEquals("Idempotency key must not be blank", keyFailure.getMessage());
        assertEquals(
                "Payment method category must not be blank",
                categoryFailure.getMessage()
        );
    }

    private Payment paymentWithAmount(String amount) {
        return Payment.create(
                PaymentId.newId(),
                MerchantReference.from(TENANT_ID, MERCHANT_ID),
                customerId(),
                sar(amount),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("payment-request-1001")
        );
    }

    private CustomerId customerId() {
        return CustomerId.from(
                UUID.fromString("fe389759-5cbc-490a-a355-835c7082dca2")
        );
    }

    private Money sar(String amount) {
        return Money.of(
                new BigDecimal(amount),
                Currency.getInstance("SAR")
        );
    }
}
