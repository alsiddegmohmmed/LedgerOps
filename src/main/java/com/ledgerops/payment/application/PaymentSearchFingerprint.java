package com.ledgerops.payment.application;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.payment.api.PaymentSearchQuery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;

final class PaymentSearchFingerprint {

    private PaymentSearchFingerprint() {
    }

    static String of(PaymentSearchQuery query) {
        AuthorizedRequestContext authorization = query.authorization();
        String merchants = authorization.merchantIds().stream()
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(","));
        String canonical = String.join("|",
                query.tenantId().toString(),
                optional(query.paymentId()),
                optional(query.merchantReference()),
                optional(query.providerId()),
                optional(query.customerId()),
                optional(query.fromInclusive()),
                optional(query.toExclusive()),
                optional(query.minimumAmount()),
                optional(query.maximumAmount()),
                optional(query.state()),
                optional(query.riskDecision()),
                optional(query.reconciliationStatus()),
                Boolean.toString(authorization.isTenantWide()),
                merchants);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String optional(Object value) {
        return Objects.toString(value, "-");
    }
}
