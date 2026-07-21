package com.ledgerops.provider.application;

import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderCallResultTests {
    @Test
    void exactResultAndDispositionMatrixAcceptsEveryApprovedCombination() {
        assertValid(ProviderResultCategory.SUCCESS, RetryDisposition.NOT_RETRYABLE, true, false);
        assertValid(ProviderResultCategory.DECLINED, RetryDisposition.NOT_RETRYABLE, true, false);
        assertValid(ProviderResultCategory.PERMANENT_FAILURE,
                RetryDisposition.NOT_RETRYABLE, false, false);
        assertValid(ProviderResultCategory.ACCEPTED,
                RetryDisposition.STATUS_RECOVERY_REQUIRED, true, false);
        assertValid(ProviderResultCategory.PENDING,
                RetryDisposition.STATUS_RECOVERY_REQUIRED, true, false);
        assertValid(ProviderResultCategory.UNKNOWN,
                RetryDisposition.STATUS_RECOVERY_REQUIRED, false, false);
        assertValid(ProviderResultCategory.TEMPORARY_FAILURE,
                RetryDisposition.SAFE_TO_RESUBMIT, false, true);
        assertValid(ProviderResultCategory.TEMPORARY_FAILURE,
                RetryDisposition.STATUS_RECOVERY_REQUIRED, true, false);
    }

    @Test
    void everyUnapprovedResultAndDispositionCombinationIsRejected() {
        for (ProviderResultCategory category : ProviderResultCategory.values()) {
            for (RetryDisposition disposition : RetryDisposition.values()) {
                boolean approved = approved(category, disposition);
                if (!approved) {
                    assertThrows(IllegalArgumentException.class,
                            () -> value(category, disposition, false, false),
                            category + " must not pair with " + disposition);
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> value(
                ProviderResultCategory.TEMPORARY_FAILURE,
                RetryDisposition.SAFE_TO_RESUBMIT, true, true));
        assertThrows(IllegalArgumentException.class, () -> value(
                ProviderResultCategory.TEMPORARY_FAILURE,
                RetryDisposition.STATUS_RECOVERY_REQUIRED, true, true));
        assertThrows(IllegalArgumentException.class, () -> value(
                ProviderResultCategory.SUCCESS, RetryDisposition.NOT_RETRYABLE,
                true, true));
    }

    private boolean approved(ProviderResultCategory category, RetryDisposition disposition) {
        return switch (category) {
            case SUCCESS, DECLINED, PERMANENT_FAILURE ->
                    disposition == RetryDisposition.NOT_RETRYABLE;
            case ACCEPTED, PENDING, UNKNOWN ->
                    disposition == RetryDisposition.STATUS_RECOVERY_REQUIRED;
            case TEMPORARY_FAILURE -> disposition == RetryDisposition.SAFE_TO_RESUBMIT
                    || disposition == RetryDisposition.STATUS_RECOVERY_REQUIRED;
        };
    }

    private void assertValid(ProviderResultCategory category, RetryDisposition disposition,
            boolean found, boolean noAcceptance) {
        assertDoesNotThrow(() -> value(category, disposition, found, noAcceptance));
    }

    private ProviderCallResult value(ProviderResultCategory category,
            RetryDisposition disposition, boolean found, boolean noAcceptance) {
        Instant now = Instant.parse("2026-07-21T03:00:00Z");
        return new ProviderCallResult(UUID.randomUUID(), UUID.randomUUID(), null,
                category, disposition, found, noAcceptance, 200, "a".repeat(64),
                "b".repeat(64), "RESPONSE", 1, null, now, now.plusMillis(1));
    }
}
