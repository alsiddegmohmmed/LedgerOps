package com.ledgerops.messaging.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        name = "ledgerops.messaging.publisher.enabled",
        havingValue = "true",
        matchIfMissing = true
)
class MessagingBacklogMetrics {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    MessagingBacklogMetrics(JdbcTemplate jdbc, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        meters.gauge("ledgerops.outbox.pending", pending);
        meters.gauge("ledgerops.outbox.oldest.age.seconds", oldestAgeSeconds);
    }

    @Scheduled(fixedDelayString = "${ledgerops.messaging.metrics.delay-ms:5000}")
    void refresh() {
        Backlog backlog = jdbc.query("""
                SELECT count(*) AS pending_count, min(created_at) AS oldest
                  FROM messaging.outbox
                 WHERE status IN ('PENDING', 'RETRYABLE', 'CLAIMED')
                """, rs -> {
            rs.next();
            var oldest = rs.getTimestamp("oldest");
            return new Backlog(
                    rs.getLong("pending_count"),
                    oldest == null ? null : oldest.toInstant()
            );
        });
        pending.set(backlog.count());
        oldestAgeSeconds.set(backlog.oldest() == null ? 0
                : Math.max(0, Duration.between(backlog.oldest(), clock.instant()).toSeconds()));
    }

    private record Backlog(long count, Instant oldest) {
    }
}
