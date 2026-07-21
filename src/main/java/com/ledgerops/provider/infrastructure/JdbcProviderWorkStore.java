package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.application.ProviderSubmissionCommand;
import com.ledgerops.provider.application.ProviderWorkConsistencyException;
import com.ledgerops.provider.application.ProviderWorkStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
class JdbcProviderWorkStore implements ProviderWorkStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcProviderWorkStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createOrVerifySubmission(ProviderSubmissionCommand command) {
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO provider.work
                    (id, tenant_id, attempt_id, payment_id, work_type, status,
                     attempt_sequence, provider_id, provider_idempotency_key, request_intent_hash,
                     command_payload, due_at, correlation_id, causation_id,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SUBMISSION', 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, attempt_id, work_type) DO NOTHING
                """, UUID.randomUUID(), command.tenantId(), command.attemptId(),
                command.paymentId(), command.attemptSequence(), command.providerId(),
                command.providerIdempotencyKey(),
                command.requestIntentHash(), command.canonicalPayload(), Timestamp.from(now),
                command.correlationId(), command.messageId(), Timestamp.from(now),
                Timestamp.from(now));

        Boolean matches = jdbc.query("""
                SELECT payment_id = ?
                   AND attempt_sequence = ?
                   AND provider_id = ?
                   AND provider_idempotency_key = ?
                   AND request_intent_hash = ?
                   AND command_payload = ?
                  FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                """, rs -> rs.next() && rs.getBoolean(1),
                command.paymentId(), command.attemptSequence(), command.providerId(),
                command.providerIdempotencyKey(),
                command.requestIntentHash(), command.canonicalPayload(), command.tenantId(),
                command.attemptId());
        if (!Boolean.TRUE.equals(matches)) {
            throw new ProviderWorkConsistencyException(
                    "Provider work identity was reused with different command content"
            );
        }
    }
}
