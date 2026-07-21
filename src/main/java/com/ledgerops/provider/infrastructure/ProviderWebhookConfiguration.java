package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.application.ProviderWebhookAuthenticator;
import com.ledgerops.provider.application.ProviderWebhookPayloadParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ledgerops.provider.webhook.enabled", havingValue = "true")
class ProviderWebhookConfiguration {
    @Bean
    ProviderWebhookAuthenticator providerWebhookAuthenticator(
            @Value("${ledgerops.provider.webhook.key-id}") String keyId,
            @Value("${ledgerops.provider.webhook.secret}") String secret,
            @Value("${ledgerops.provider.webhook.provider-client-id}") String providerClientId,
            Clock clock
    ) {
        return new WebhookHmacAuthenticator(keyId, secret, providerClientId, clock);
    }

    @Bean
    ProviderWebhookPayloadParser providerWebhookPayloadParser() {
        return new JacksonProviderWebhookPayloadParser();
    }
}
