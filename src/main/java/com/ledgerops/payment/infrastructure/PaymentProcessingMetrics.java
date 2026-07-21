package com.ledgerops.payment.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicInteger;

@Component
class PaymentProcessingMetrics {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicInteger stuck = new AtomicInteger();

    PaymentProcessingMetrics(JdbcTemplate jdbc, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        Gauge.builder("ledgerops.payment.processing.stuck", stuck, AtomicInteger::get)
                .description("Payments PROCESSING for more than 15 minutes")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${ledgerops.payment.metrics.delay-ms:5000}")
    void refresh() {
        stuck.set(jdbc.queryForObject("""
                SELECT count(*) FROM payment.payments
                 WHERE status = 'PROCESSING' AND updated_at < ?
                """, Integer.class, Timestamp.from(clock.instant().minusSeconds(900))));
    }
}
