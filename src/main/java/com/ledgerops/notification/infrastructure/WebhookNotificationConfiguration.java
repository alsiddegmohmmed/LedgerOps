package com.ledgerops.notification.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "ledgerops.notification.enabled", havingValue = "true")
class WebhookNotificationConfiguration {

    @Bean
    WebhookSecretCipher webhookSecretCipher(
            @Value("${ledgerops.notification.master-key-base64}") String masterKey,
            @Value("${ledgerops.notification.key-version:v1}") String keyVersion
    ) {
        return new WebhookSecretCipher(masterKey, keyVersion);
    }

    @Bean
    WebhookUrlPolicy webhookUrlPolicy(
            @Value("${ledgerops.notification.allow-local:false}") boolean allowLocal
    ) {
        return new WebhookUrlPolicy(allowLocal);
    }
}
