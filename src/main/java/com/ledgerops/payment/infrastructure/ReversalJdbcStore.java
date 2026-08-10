package com.ledgerops.payment.infrastructure;

import com.ledgerops.payment.application.ReversalStore;
import com.ledgerops.payment.application.AcceptedFinalReversalResult;
import com.ledgerops.payment.application.ReversalProviderResultStore;
import com.ledgerops.payment.application.ReversalRetryApplication;
import com.ledgerops.payment.application.ReversalRetryStore;
import com.ledgerops.payment.api.ReversalDetailsQuery;
import com.ledgerops.payment.api.ReversalDetailsSnapshot;
import com.ledgerops.payment.domain.AttemptSubjectType;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentAttemptId;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.ProviderId;
import com.ledgerops.payment.domain.Reversal;
import com.ledgerops.payment.domain.ReversalId;
import com.ledgerops.payment.domain.ReversalStatus;
import com.ledgerops.provider.api.ProviderResultCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class ReversalJdbcStore implements ReversalStore, ReversalProviderResultStore,
        ReversalRetryStore, ReversalDetailsQuery {

    private static final String SELECT_COLUMNS = """
            id, tenant_id, payment_id, merchant_id, amount, currency, status,
            requested_by, request_reason, requested_at, processing_at, failed_at,
            completed_at, failure_category, version
            """;

    private static final String FIND_BY_PAYMENT_SQL = """
            SELECT %s
              FROM payment.reversals
             WHERE tenant_id = ? AND payment_id = ?
            """.formatted(SELECT_COLUMNS);

    private static final String FIND_BY_ID_SQL = """
            SELECT %s
              FROM payment.reversals
             WHERE tenant_id = ? AND id = ?
            """.formatted(SELECT_COLUMNS);

    private static final String LOCK_BY_ID_SQL = """
            SELECT %s
              FROM payment.reversals
             WHERE tenant_id = ? AND id = ?
             FOR UPDATE
            """.formatted(SELECT_COLUMNS);

    private static final String INSERT_SQL = """
            INSERT INTO payment.reversals (
                id, tenant_id, payment_id, merchant_id, amount, currency, status,
                requested_by, request_reason, requested_at, processing_at, failed_at,
                completed_at, failure_category, version, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_ACCEPTED_FINAL_SQL = """
            SELECT tenant_id, reversal_id, payment_id, attempt_id, provider_evidence_id,
                   provider_result_id, final_category, provider_reference, applied_at
              FROM payment.accepted_final_reversal_results
             WHERE tenant_id = ? AND reversal_id = ?
            """;

    private static final String INSERT_ACCEPTED_FINAL_SQL = """
            INSERT INTO payment.accepted_final_reversal_results (
                tenant_id, reversal_id, payment_id, attempt_id, provider_evidence_id,
                provider_result_id, final_category, provider_reference, applied_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String ATTEMPT_COLUMNS = """
            id, tenant_id, payment_id, attempt_subject_type, attempt_subject_id,
            sequence, provider_id, provider_idempotency_key, initiated_at, merchant_id,
            customer_id, amount, currency, payment_method_category, request_intent_hash
            """;

    private static final String FIND_ATTEMPT_BY_ID_SQL = """
            SELECT %s
              FROM payment.payment_attempts
             WHERE tenant_id = ? AND payment_id = ? AND id = ?
            """.formatted(ATTEMPT_COLUMNS);

    private static final String FIND_LATEST_REVERSAL_ATTEMPT_SQL = """
            SELECT %s
              FROM payment.payment_attempts
             WHERE tenant_id = ?
               AND payment_id = ?
               AND attempt_subject_type = 'REVERSAL'
               AND attempt_subject_id = ?
             ORDER BY sequence DESC
             LIMIT 1
            """.formatted(ATTEMPT_COLUMNS);

    private static final String INSERT_ATTEMPT_SQL = """
            INSERT INTO payment.payment_attempts (
                id, tenant_id, payment_id, attempt_subject_type, attempt_subject_id,
                sequence, provider_id, provider_idempotency_key, initiated_at,
                merchant_id, customer_id, amount, currency, payment_method_category,
                request_intent_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_RETRY_APPLICATION_SQL = """
            SELECT tenant_id, reversal_id, payment_id, previous_attempt_id,
                   new_attempt_id, provider_evidence_id, provider_id, request_reason,
                   requested_at, applied_at
              FROM payment.reversal_retry_applications
             WHERE tenant_id = ? AND reversal_id = ? AND previous_attempt_id = ?
            """;

    private static final String INSERT_RETRY_APPLICATION_SQL = """
            INSERT INTO payment.reversal_retry_applications (
                tenant_id, reversal_id, payment_id, previous_attempt_id, new_attempt_id,
                provider_evidence_id, provider_id, request_reason, requested_at, applied_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String COMPARE_AND_SET_SQL = """
            UPDATE payment.reversals
               SET status = ?,
                   processing_at = ?,
                   failed_at = ?,
                   completed_at = ?,
                   failure_category = ?,
                   version = version + 1,
                   updated_at = ?
             WHERE tenant_id = ?
               AND id = ?
               AND version = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    ReversalJdbcStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public Optional<Reversal> findByTenantAndPayment(UUID tenantId, PaymentId paymentId) {
        return queryOne(FIND_BY_PAYMENT_SQL, tenantId, paymentId.value());
    }

    @Override
    public Optional<ReversalDetailsSnapshot> findByTenantAndPayment(UUID tenantId, UUID paymentId) {
        return findByTenantAndPayment(tenantId, PaymentId.from(paymentId))
                .map(ReversalDetailsSnapshot::from);
    }

    @Override
    public Optional<Reversal> findByTenantAndId(UUID tenantId, ReversalId reversalId) {
        return queryOne(FIND_BY_ID_SQL, tenantId, reversalId.value());
    }

    @Override
    public Optional<Reversal> lockByTenantAndId(UUID tenantId, ReversalId reversalId) {
        return queryOne(LOCK_BY_ID_SQL, tenantId, reversalId.value());
    }

    @Override
    public Reversal insert(Reversal reversal) {
        Objects.requireNonNull(reversal, "Reversal must not be null");
        int inserted = jdbcTemplate.update(
                INSERT_SQL,
                reversal.id().value(),
                reversal.tenantId(),
                reversal.paymentId().value(),
                reversal.merchantId(),
                reversal.amount().amount(),
                reversal.amount().currency().getCurrencyCode(),
                reversal.status().name(),
                reversal.requestedBy(),
                reversal.requestReason(),
                Timestamp.from(reversal.requestedAt()),
                timestamp(reversal.processingAt()),
                timestamp(reversal.failedAt()),
                timestamp(reversal.completedAt()),
                reversal.failureCategory(),
                reversal.version(),
                Timestamp.from(clock.instant())
        );
        if (inserted != 1) {
            throw new IllegalStateException("Reversal insert did not create exactly one row");
        }
        return reversal;
    }

    @Override
    public boolean compareAndSet(Reversal updatedReversal, long expectedVersion) {
        Objects.requireNonNull(updatedReversal, "Updated Reversal must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Expected Reversal version must not be negative");
        }
        return jdbcTemplate.update(
                COMPARE_AND_SET_SQL,
                updatedReversal.status().name(),
                timestamp(updatedReversal.processingAt()),
                timestamp(updatedReversal.failedAt()),
                timestamp(updatedReversal.completedAt()),
                updatedReversal.failureCategory(),
                Timestamp.from(clock.instant()),
                updatedReversal.tenantId(),
                updatedReversal.id().value(),
                expectedVersion
        ) == 1;
    }

    @Override
    public Optional<PaymentAttempt> findAttemptById(
            UUID tenantId,
            PaymentId paymentId,
            UUID attemptId
    ) {
        return jdbcTemplate.query(
                FIND_ATTEMPT_BY_ID_SQL,
                this::mapAttempt,
                tenantId,
                paymentId.value(),
                attemptId
        ).stream().findFirst();
    }

    @Override
    public Optional<PaymentAttempt> findLatestReversalAttempt(
            UUID tenantId,
            PaymentId paymentId,
            ReversalId reversalId
    ) {
        return jdbcTemplate.query(
                FIND_LATEST_REVERSAL_ATTEMPT_SQL,
                this::mapAttempt,
                tenantId,
                paymentId.value(),
                reversalId.value()
        ).stream().findFirst();
    }

    @Override
    public void insertAttempt(PaymentAttempt attempt) {
        jdbcTemplate.update(
                INSERT_ATTEMPT_SQL,
                attempt.attemptId().value(),
                attempt.tenantId(),
                attempt.paymentId().value(),
                attempt.subjectType().name(),
                attempt.subjectId(),
                attempt.sequence(),
                attempt.providerId().name(),
                attempt.providerIdempotencyKey(),
                Timestamp.from(attempt.initiatedAt()),
                attempt.merchantId(),
                attempt.customerId().value(),
                attempt.amount().amount(),
                attempt.amount().currency().getCurrencyCode(),
                attempt.paymentMethodCategory().value(),
                attempt.requestIntentHash()
        );
    }

    @Override
    public Optional<ReversalRetryApplication> findRetryApplication(
            UUID tenantId,
            ReversalId reversalId,
            UUID previousAttemptId
    ) {
        return jdbcTemplate.query(
                FIND_RETRY_APPLICATION_SQL,
                (rs, rowNumber) -> new ReversalRetryApplication(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("reversal_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("previous_attempt_id", UUID.class),
                        rs.getObject("new_attempt_id", UUID.class),
                        rs.getObject("provider_evidence_id", UUID.class),
                        rs.getString("provider_id"),
                        rs.getString("request_reason"),
                        rs.getTimestamp("requested_at").toInstant(),
                        rs.getTimestamp("applied_at").toInstant()
                ),
                tenantId,
                reversalId.value(),
                previousAttemptId
        ).stream().findFirst();
    }

    @Override
    public void insertRetryApplication(ReversalRetryApplication application) {
        jdbcTemplate.update(
                INSERT_RETRY_APPLICATION_SQL,
                application.tenantId(),
                application.reversalId(),
                application.paymentId(),
                application.previousAttemptId(),
                application.newAttemptId(),
                application.providerEvidenceId(),
                application.providerId(),
                application.requestReason(),
                Timestamp.from(application.requestedAt()),
                Timestamp.from(application.appliedAt())
        );
    }

    @Override
    public Optional<AcceptedFinalReversalResult> findAcceptedFinalResult(
            UUID tenantId,
            ReversalId reversalId
    ) {
        return jdbcTemplate.query(
                FIND_ACCEPTED_FINAL_SQL,
                (rs, rowNumber) -> new AcceptedFinalReversalResult(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("reversal_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("attempt_id", UUID.class),
                        rs.getObject("provider_evidence_id", UUID.class),
                        rs.getObject("provider_result_id", UUID.class),
                        ProviderResultCategory.valueOf(rs.getString("final_category")),
                        rs.getString("provider_reference"),
                        rs.getTimestamp("applied_at").toInstant()
                ),
                tenantId,
                reversalId.value()
        ).stream().findFirst();
    }

    @Override
    public void insertAcceptedFinalResult(AcceptedFinalReversalResult result) {
        jdbcTemplate.update(
                INSERT_ACCEPTED_FINAL_SQL,
                result.tenantId(),
                result.reversalId(),
                result.paymentId(),
                result.attemptId(),
                result.providerEvidenceId(),
                result.providerResultId(),
                result.finalCategory().name(),
                result.providerReference(),
                Timestamp.from(result.appliedAt())
        );
    }

    private Optional<Reversal> queryOne(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, this::map, arguments).stream().findFirst();
    }

    private Reversal map(ResultSet rs, int rowNumber) throws SQLException {
        return Reversal.rehydrate(
                ReversalId.from(rs.getObject("id", UUID.class)),
                rs.getObject("tenant_id", UUID.class),
                PaymentId.from(rs.getObject("payment_id", UUID.class)),
                rs.getObject("merchant_id", UUID.class),
                Money.of(
                        rs.getBigDecimal("amount"),
                        Currency.getInstance(rs.getString("currency"))
                ),
                ReversalStatus.valueOf(rs.getString("status")),
                rs.getObject("requested_by", UUID.class),
                rs.getString("request_reason"),
                rs.getTimestamp("requested_at").toInstant(),
                instant(rs, "processing_at"),
                instant(rs, "failed_at"),
                instant(rs, "completed_at"),
                rs.getString("failure_category"),
                rs.getLong("version")
        );
    }

    private PaymentAttempt mapAttempt(ResultSet rs, int rowNumber) throws SQLException {
        return new PaymentAttempt(
                PaymentAttemptId.from(rs.getObject("id", UUID.class)),
                rs.getObject("tenant_id", UUID.class),
                PaymentId.from(rs.getObject("payment_id", UUID.class)),
                AttemptSubjectType.valueOf(rs.getString("attempt_subject_type")),
                rs.getObject("attempt_subject_id", UUID.class),
                rs.getInt("sequence"),
                ProviderId.valueOf(rs.getString("provider_id")),
                rs.getString("provider_idempotency_key"),
                rs.getTimestamp("initiated_at").toInstant(),
                rs.getObject("merchant_id", UUID.class),
                CustomerId.from(rs.getObject("customer_id", UUID.class)),
                Money.of(
                        rs.getBigDecimal("amount"),
                        Currency.getInstance(rs.getString("currency"))
                ),
                PaymentMethodCategory.from(rs.getString("payment_method_category")),
                rs.getString("request_intent_hash")
        );
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
