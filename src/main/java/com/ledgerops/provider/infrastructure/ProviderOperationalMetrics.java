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
    private final AtomicInteger webhookBacklog = new AtomicInteger();

    ProviderOperationalMetrics(JdbcTemplate jdbc, CircuitBreaker circuitBreaker,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.circuitBreaker = circuitBreaker;
        Gauge.builder("ledgerops.provider.retry.backlog", retryBacklog, AtomicInteger::get)
                .register(meters);
        Gauge.builder("ledgerops.provider.recovery.backlog", recoveryBacklog, AtomicInteger::get)
                .register(meters);
        Gauge.builder("ledgerops.webhook.backlog", webhookBacklog, AtomicInteger::get)
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
        retryBacklog.set(countRetryBacklog());
        recoveryBacklog.set(countRecoveryBacklog());
        webhookBacklog.set(jdbc.queryForObject("""
                SELECT count(*) FROM provider.webhook_events
                 WHERE status IN ('PENDING', 'CLAIMED')
                """, Integer.class));
    }

    private int countRetryBacklog() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM provider.work w
                 WHERE w.work_type = 'SUBMISSION'
                   AND (
                       w.status = 'WAITING_RETRY_REQUEST'
                       OR (w.status = 'CLAIMED' AND EXISTS (
                           SELECT 1 FROM provider.results r
                            WHERE r.tenant_id = w.tenant_id
                              AND r.attempt_id = w.attempt_id
                              AND r.retry_disposition = 'SAFE_TO_RESUBMIT'
                       ))
                   )
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private int countRecoveryBacklog() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT DISTINCT tenant_id, attempt_id
                      FROM provider.work
                     WHERE (work_type = 'SUBMISSION' AND status = 'WAITING_STATUS')
                        OR (work_type = 'STATUS_QUERY'
                            AND status IN ('PENDING', 'RETRYABLE', 'CLAIMED', 'WAITING_STATUS'))
                ) recovery_attempts
                """, Integer.class);
        return count == null ? 0 : count;
    }
}
