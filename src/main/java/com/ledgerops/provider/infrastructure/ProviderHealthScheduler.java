package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderHealthPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(name = "ledgerops.provider.health.enabled", havingValue = "true")
class ProviderHealthScheduler {

    private final ProviderHealthPort health;
    private final ObjectProvider<CircuitBreaker> circuitBreakers;
    private final Clock clock;

    ProviderHealthScheduler(
            ProviderHealthPort health,
            ObjectProvider<CircuitBreaker> circuitBreakers,
            Clock clock
    ) {
        this.health = health;
        this.circuitBreakers = circuitBreakers;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ledgerops.provider.health.evaluation-delay-ms:30000}")
    void evaluateSimulator() {
        CircuitBreaker circuitBreaker = circuitBreakers.getIfAvailable();
        health.evaluate(
                "SIMULATOR",
                circuitBreaker == null ? "CLOSED" : circuitBreaker.getState().name(),
                clock.instant());
    }
}
