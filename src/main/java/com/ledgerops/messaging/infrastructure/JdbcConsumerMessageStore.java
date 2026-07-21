package com.ledgerops.messaging.infrastructure;

import com.ledgerops.messaging.api.ConsumerFailureResult;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.InboxResult;
import com.ledgerops.messaging.api.IncomingMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
class JdbcConsumerMessageStore implements ConsumerMessageStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcConsumerMessageStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public InboxResult recordProcessed(IncomingMessage message) {
        int inserted = jdbc.update("""
                INSERT INTO messaging.inbox
                    (consumer_name, message_id, tenant_id, message_type, status, recorded_at)
                VALUES (?, ?, ?, ?, 'PROCESSED', ?)
                ON CONFLICT (consumer_name, message_id) DO NOTHING
                """, message.consumerName(), message.messageId(), message.tenantId(),
                message.messageType(), Timestamp.from(clock.instant()));
        if (inserted == 0) {
            return InboxResult.DUPLICATE;
        }
        jdbc.update("""
                DELETE FROM messaging.consumer_failures
                 WHERE consumer_name = ? AND message_id = ?
                """, message.consumerName(), message.messageId());
        return InboxResult.PROCESSED;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConsumerFailureResult recordFailure(
            IncomingMessage message,
            String rawEnvelope,
            String payloadHash,
            String topic,
            int partition,
            long offset,
            String reasonCode,
            String safeSummary,
            UUID correlationId,
            boolean immediatelyDead
    ) {
        if (alreadyTerminal(message)) {
            return new ConsumerFailureResult(5, true);
        }
        Instant now = clock.instant();
        int failureCount;
        if (immediatelyDead) {
            failureCount = 5;
        } else {
            failureCount = jdbc.query("""
                    INSERT INTO messaging.consumer_failures
                        (consumer_name, message_id, tenant_id, failure_count,
                         first_failed_at, last_failed_at, last_reason)
                    VALUES (?, ?, ?, 1, ?, ?, ?)
                    ON CONFLICT (consumer_name, message_id) DO UPDATE
                       SET failure_count = LEAST(5, messaging.consumer_failures.failure_count + 1),
                           last_failed_at = EXCLUDED.last_failed_at,
                           last_reason = EXCLUDED.last_reason
                    RETURNING failure_count
                    """, rs -> rs.next() ? rs.getInt(1) : 1,
                    message.consumerName(), message.messageId(), message.tenantId(),
                    Timestamp.from(now), Timestamp.from(now), bounded(safeSummary));
        }
        if (failureCount >= 5) {
            jdbc.update("""
                    INSERT INTO messaging.inbox
                        (consumer_name, message_id, tenant_id, message_type, status, recorded_at)
                    VALUES (?, ?, ?, ?, 'DEAD', ?)
                    ON CONFLICT (consumer_name, message_id) DO NOTHING
                    """, message.consumerName(), message.messageId(), message.tenantId(),
                    message.messageType(), Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO messaging.consumer_dead_letters
                        (id, consumer_name, message_id, tenant_id, message_type, envelope,
                         payload_hash, topic, partition_number, record_offset, reason_code,
                         safe_summary, correlation_id, first_failed_at, dead_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (consumer_name, message_id) DO NOTHING
                    """, UUID.randomUUID(), message.consumerName(), message.messageId(),
                    message.tenantId(), message.messageType(), rawEnvelope, payloadHash,
                    topic, partition, offset, reasonCode, bounded(safeSummary), correlationId,
                    Timestamp.from(firstFailureAt(message, now)), Timestamp.from(now));
        }
        return new ConsumerFailureResult(failureCount, failureCount >= 5);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTransportDeadLetter(
            String consumerName,
            String topic,
            int partition,
            long offset,
            String rawRecordHash,
            byte[] boundedSafeBytes,
            String reasonCode,
            String safeSummary,
            UUID correlationId
    ) {
        byte[] boundedBytes = boundedSafeBytes == null ? null
                : java.util.Arrays.copyOf(boundedSafeBytes, Math.min(4096, boundedSafeBytes.length));
        jdbc.update("""
                INSERT INTO messaging.transport_dead_letters
                    (id, consumer_name, topic, partition_number, record_offset,
                     raw_record_hash, bounded_safe_bytes, safe_summary, reason_code,
                     correlation_id, dead_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (consumer_name, topic, partition_number, record_offset) DO NOTHING
                """, UUID.randomUUID(), consumerName, topic, partition, offset,
                rawRecordHash, boundedBytes, bounded(safeSummary), reasonCode,
                correlationId, Timestamp.from(clock.instant()));
    }

    private boolean alreadyTerminal(IncomingMessage message) {
        return Boolean.TRUE.equals(jdbc.query("""
                SELECT status = 'DEAD' FROM messaging.inbox
                 WHERE consumer_name = ? AND message_id = ?
                """, rs -> rs.next() && rs.getBoolean(1),
                message.consumerName(), message.messageId()));
    }

    private Instant firstFailureAt(IncomingMessage message, Instant fallback) {
        return jdbc.query("""
                SELECT first_failed_at FROM messaging.consumer_failures
                 WHERE consumer_name = ? AND message_id = ?
                """, rs -> rs.next() ? rs.getTimestamp(1).toInstant() : fallback,
                message.consumerName(), message.messageId());
    }

    private String bounded(String value) {
        String safe = value == null || value.isBlank() ? "No safe detail" : value;
        return safe.length() <= 512 ? safe : safe.substring(0, 512);
    }
}
