package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class PaymentRequestFingerprint {

    private PaymentRequestFingerprint() {
    }

    static String from(Payment payment) {
        String canonicalRequest = String.join(
                "\n",
                payment.merchantReference().value().toString(),
                payment.customerId().value().toString(),
                payment.amount().amount().toPlainString(),
                payment.amount().currency().getCurrencyCode(),
                payment.paymentMethodCategory().value()
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
