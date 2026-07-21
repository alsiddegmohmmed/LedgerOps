package com.ledgerops.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

class RequestIntentHashTests {

    @Test
    void hashesTheExactCanonicalRequestIntentBytes() {
        Payment payment = Payment.create(
                PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                MerchantReference.from(
                        UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        UUID.fromString("00000000-0000-0000-0000-000000000002")
                ),
                CustomerId.from(UUID.fromString("00000000-0000-0000-0000-000000000003")),
                Money.of(new BigDecimal("12.30"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("CARD"),
                IdempotencyKey.from("hash-fixture")
        );

        assertEquals(
                "{\"providerId\":\"SIMULATOR\",\"merchantId\":\"00000000-0000-0000-0000-000000000002\",\"customerId\":\"00000000-0000-0000-0000-000000000003\",\"amount\":\"12.30\",\"currency\":\"SAR\",\"paymentMethodCategory\":\"CARD\"}",
                RequestIntentHash.canonicalJson(payment)
        );
        assertEquals(
                "a759a31b7d2ca555352b5c6787514437e1e9c49cc538c8ed7c2872ac4ada8a5e",
                RequestIntentHash.calculate(payment)
        );
    }

    @Test
    void canonicalJsonEscapesControlCharactersAndPreservesUnicode() throws Exception {
        String category = "CARD\"\\\n\t☃";
        Payment payment = Payment.create(
                PaymentId.from(UUID.randomUUID()),
                MerchantReference.from(UUID.randomUUID(), UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("12.30"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from(category),
                IdempotencyKey.from("escaping-fixture")
        );

        String canonical = RequestIntentHash.canonicalJson(payment);

        assertEquals(
                category,
                JsonMapper.builder().build().readTree(canonical)
                        .get("paymentMethodCategory").asText()
        );
        assertEquals(-1, canonical.indexOf('\n'));
        assertEquals(-1, canonical.indexOf('\t'));
    }
}
