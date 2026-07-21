package com.ledgerops.provider.infrastructure;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "ledgerops.provider.execution.enabled", havingValue = "true")
class ProviderExecutionConfiguration {
    @Bean
    ProviderHmacSigner providerHmacSigner(
            @Value("${ledgerops.provider.simulator.key-id}") String keyId,
            @Value("${ledgerops.provider.simulator.secret}") String secret) {
        return new ProviderHmacSigner(keyId, secret);
    }

    @Bean
    CircuitBreaker providerCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20).minimumNumberOfCalls(10)
                .failureRateThreshold(50).slowCallRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3).build();
        return CircuitBreaker.of("provider-simulator", config);
    }

    @Bean
    Bulkhead providerBulkhead() {
        return Bulkhead.of("provider-simulator", BulkheadConfig.custom()
                .maxConcurrentCalls(10).maxWaitDuration(Duration.ZERO).build());
    }

    @Bean
    SimulatorProviderGateway simulatorProviderGateway(
            @Value("${ledgerops.provider.simulator.base-url}") URI baseUri,
            ProviderHmacSigner signer, CircuitBreaker circuitBreaker,
            Bulkhead bulkhead, Clock clock, Tracer tracer) {
        return new SimulatorProviderGateway(
                baseUri, signer, circuitBreaker, bulkhead, clock, tracer
        );
    }
}
