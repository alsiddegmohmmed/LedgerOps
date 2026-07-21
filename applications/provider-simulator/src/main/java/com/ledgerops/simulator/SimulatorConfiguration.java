package com.ledgerops.simulator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.URI;
import java.time.Clock;

@Configuration
@EnableScheduling
class SimulatorConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "ledgerops.simulator.webhook.enabled", havingValue = "true")
    SimulatorWebhookSender simulatorWebhookSender(
            @Value("${ledgerops.simulator.webhook.core-base-url}") URI coreBaseUrl,
            @Value("${ledgerops.simulator.webhook.key-id}") String keyId,
            @Value("${ledgerops.simulator.webhook.secret}") String secret,
            Clock clock
    ) {
        return new SimulatorWebhookSender(
                coreBaseUrl, new SimulatorWebhookSigner(keyId, secret), clock);
    }
}
