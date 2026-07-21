package com.ledgerops.provider.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "ledgerops.provider.execution.enabled", havingValue = "true")
class ProviderOperationalMetrics {
    private final JdbcTemplate jdbc;
    private final CircuitBreaker circuitBreaker;
    private final AtomicInteger retryBacklog = new AtomicInteger();
    private final AtomicInteger recoveryBacklog = new AtomicInteger();

    ProviderOperationalMetrics(JdbcTemplate jdbc, CircuitBreaker circuitBreaker,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.circuitBreaker = circuitBreaker;
        Gauge.builder("ledgerops.provider.retry.backlog", retryBacklog, AtomicInteger::get)
                .register(meters);
        Gauge.builder("ledgerops.provider.recovery.backlog", recoveryBacklog, AtomicInteger::get)
                .register(meters);
        for (CircuitBreaker.State state : CircuitBreaker.State.values()) {
            Gauge.builder("ledgerops.provider.circuit.state", circuitBreaker,
                            breaker -> breaker.getState() == state ? 1 : 0)
                    .tag("circuit", "simulator")
                    .tag("state", state.name().toLowerCase(Locale.ROOT))
                    .register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${ledgerops.provider.metrics.delay-ms:5000}")
    void refresh() {
        retryBacklog.set(count("WAITING_RETRY_REQUEST"));
        recoveryBacklog.set(count("WAITING_STATUS"));
    }

    private int count(String status) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM provider.work WHERE status = ?", Integer.class, status);
        return count == null ? 0 : count;
    }
}
