package com.ledgerops.payment.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

final class PaymentPageCursorCodec {

    private static final String VERSION = "1";

    private PaymentPageCursorCodec() {
    }

    static String encode(PaymentPageCursor cursor) {
        Objects.requireNonNull(cursor, "Payment cursor must not be null");
        String payload = String.join("|",
                VERSION,
                cursor.createdAt().toString(),
                cursor.paymentId().toString(),
                cursor.queryFingerprint());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
    }

    static PaymentPageCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 2048) {
            throw new InvalidPaymentCursorException();
        }
        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 4 || !VERSION.equals(parts[0])
                    || parts[3].isBlank() || parts[3].length() != 64) {
                throw new InvalidPaymentCursorException();
            }
            return new PaymentPageCursor(
                    Integer.parseInt(parts[0]),
                    Instant.parse(parts[1]),
                    UUID.fromString(parts[2]),
                    parts[3]);
        } catch (InvalidPaymentCursorException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidPaymentCursorException();
        }
    }
}

record PaymentPageCursor(
        int version,
        Instant createdAt,
        UUID paymentId,
        String queryFingerprint
) {
    PaymentPageCursor {
        Objects.requireNonNull(createdAt, "Cursor timestamp must not be null");
        Objects.requireNonNull(paymentId, "Cursor Payment ID must not be null");
        Objects.requireNonNull(queryFingerprint, "Cursor query fingerprint must not be null");
    }
}
