package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.ProviderId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;

final class RequestIntentHash {

    private RequestIntentHash() {
    }

    static String canonicalJson(Payment payment) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("providerId", ProviderId.SIMULATOR.name());
        fields.put("merchantId", payment.merchantReference().value().toString());
        fields.put("customerId", payment.customerId().value().toString());
        fields.put("amount", payment.amount().amount().toPlainString());
        fields.put("currency", payment.amount().currency().getCurrencyCode());
        fields.put("paymentMethodCategory", payment.paymentMethodCategory().value());
        return CanonicalJson.object(fields);
    }

    static String calculate(Payment payment) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonicalJson(payment).getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

}
