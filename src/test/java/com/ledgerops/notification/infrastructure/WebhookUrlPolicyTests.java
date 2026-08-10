package com.ledgerops.notification.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookUrlPolicyTests {

    @Test
    void rejectsCredentialsQueryFragmentsAndNonHttpsUrls() {
        WebhookUrlPolicy policy = new WebhookUrlPolicy(false);

        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("https://user:pass@example.com/webhook"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("https://example.com/webhook?secret=1"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("https://example.com/webhook#fragment"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("http://example.com/webhook"));
    }

    @Test
    void localHttpIsAllowedOnlyWhenTheExplicitLocalPolicyIsEnabled() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebhookUrlPolicy(false).validate("http://localhost:8081/webhook"));
        assertDoesNotThrow(() -> new WebhookUrlPolicy(true)
                .validate("http://localhost:8081/webhook"));
        assertThrows(IllegalArgumentException.class,
                () -> new WebhookUrlPolicy(false).validate("https://127.0.0.1/webhook"));
    }
}
