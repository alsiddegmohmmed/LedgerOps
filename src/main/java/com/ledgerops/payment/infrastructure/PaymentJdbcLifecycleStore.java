package com.ledgerops.payment.infrastructure;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.application.PaymentCompletionStore;
import com.ledgerops.payment.application.AcceptedFinalProviderResult;
import com.ledgerops.payment.application.PaymentLifecycleStore;
import com.ledgerops.payment.application.PaymentProviderResultStore;
import com.ledgerops.payment.application.PaymentSubmissionStore;
import com.ledgerops.payment.application.PaymentRetryApplication;
import com.ledgerops.payment.application.PaymentRetryStore;
import com.ledgerops.payment.application.VersionedPayment;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentAttemptId;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.ProviderId;
import com.ledgerops.provider.api.ProviderResultCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class PaymentJdbcLifecycleStore implements PaymentLifecycleStore, PaymentCompletionStore,
        PaymentSubmissionStore, PaymentProviderResultStore, PaymentRetryStore {

    private static final String FIND_SQL = """
            SELECT id,
                   tenant_id,
                   merchant_id,
                   customer_id,
                   amount,
                   currency,
                   payment_method_category,
                   idempotency_key,
                   status,
                   version
              FROM payment.payments
             WHERE tenant_id = ?
               AND id = ?
            """;

    private static final String COMPARE_AND_SET_SQL = """
            UPDATE payment.payments
               SET status = ?,
                   version = version + 1,
                   updated_at = ?
             WHERE tenant_id = ?
               AND id = ?
               AND version = ?
            """;

    private static final String FIND_FOR_UPDATE_SQL = """
            SELECT id,
                   tenant_id,
                   merchant_id,
                   customer_id,
                   amount,
                   currency,
                   payment_method_category,
                   idempotency_key,
                   status,
                   version
              FROM payment.payments
             WHERE tenant_id = ?
               AND id = ?
               FOR UPDATE
            """;
    private static final String FIND_ATTEMPT_SQL = """
            SELECT id, tenant_id, payment_id, sequence, provider_id,
                   provider_idempotency_key, initiated_at, merchant_id,
                   customer_id, amount, currency, payment_method_category,
                   request_intent_hash
              FROM payment.payment_attempts
             WHERE tenant_id = ? AND payment_id = ? AND sequence = ?
            """;
    private static final String FIND_ATTEMPT_BY_ID_SQL = """
            SELECT id, tenant_id, payment_id, sequence, provider_id,
                   provider_idempotency_key, initiated_at, merchant_id,
                   customer_id, amount, currency, payment_method_category,
                   request_intent_hash
              FROM payment.payment_attempts
             WHERE tenant_id = ? AND payment_id = ? AND id = ?
            """;
    private static final String FIND_ACCEPTED_FINAL_SQL = """
            SELECT tenant_id, payment_id, attempt_id, provider_evidence_id,
                   provider_result_id, final_category, provider_reference, applied_at
              FROM payment.accepted_final_provider_results
             WHERE tenant_id = ? AND payment_id = ?
            """;
    private static final String INSERT_ACCEPTED_FINAL_SQL = """
            INSERT INTO payment.accepted_final_provider_results (
                tenant_id, payment_id, attempt_id, provider_evidence_id,
                provider_result_id, final_category, provider_reference, applied_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_ATTEMPT_SQL = """
            INSERT INTO payment.payment_attempts (
                id, tenant_id, payment_id, sequence, provider_id,
                provider_idempotency_key, initiated_at, merchant_id,
                customer_id, amount, currency, payment_method_category,
                request_intent_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND_LATEST_ATTEMPT_SQL = """
            SELECT id, tenant_id, payment_id, sequence, provider_id,
                   provider_idempotency_key, initiated_at, merchant_id,
                   customer_id, amount, currency, payment_method_category,
                   request_intent_hash
              FROM payment.payment_attempts
             WHERE tenant_id = ? AND payment_id = ?
             ORDER BY sequence DESC
             LIMIT 1
            """;
    private static final String FIND_RETRY_APPLICATION_SQL = """
            SELECT tenant_id, retry_request_id, payment_id, previous_attempt_id,
                   new_attempt_id, provider_evidence_id, provider_id, requested_at, applied_at
              FROM payment.retry_applications
             WHERE tenant_id = ? AND retry_request_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    PaymentJdbcLifecycleStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public Optional<VersionedPayment> findByTenantAndId(
            UUID tenantId,
            PaymentId paymentId
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        return jdbcTemplate.query(
                        FIND_SQL,
                        this::mapVersionedPayment,
                        tenantId,
                        paymentId.value()
                )
                .stream()
                .findFirst();
    }

    @Override
    public Optional<VersionedPayment> lockByTenantAndId(
            UUID tenantId,
            PaymentId paymentId
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        return jdbcTemplate.query(
                        FIND_FOR_UPDATE_SQL,
                        this::mapVersionedPayment,
                        tenantId,
                        paymentId.value()
                )
                .stream()
                .findFirst();
    }

    @Override
    public boolean compareAndSet(Payment updatedPayment, long expectedVersion) {
        Objects.requireNonNull(updatedPayment, "Updated Payment must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "Expected Payment persistence version must not be negative"
            );
        }

        return jdbcTemplate.update(
                COMPARE_AND_SET_SQL,
                updatedPayment.status().name(),
                Timestamp.from(clock.instant()),
                updatedPayment.tenantId(),
                updatedPayment.id().value(),
                expectedVersion
        ) == 1;
    }

    @Override
    public Optional<PaymentAttempt> findAttempt(
            UUID tenantId,
            PaymentId paymentId,
            int sequence
    ) {
        return jdbcTemplate.query(
                FIND_ATTEMPT_SQL,
                this::mapAttempt,
                tenantId,
                paymentId.value(),
                sequence
        ).stream().findFirst();
    }

    @Override
    public void insertAttempt(PaymentAttempt attempt) {
        jdbcTemplate.update(
                INSERT_ATTEMPT_SQL,
                attempt.attemptId().value(),
                attempt.tenantId(),
                attempt.paymentId().value(),
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
    public Optional<PaymentAttempt> findLatestAttempt(UUID tenantId, PaymentId paymentId) {
        return jdbcTemplate.query(
                FIND_LATEST_ATTEMPT_SQL, this::mapAttempt, tenantId, paymentId.value()
        ).stream().findFirst();
    }

    @Override
    public Optional<PaymentRetryApplication> findRetryApplication(
            UUID tenantId, UUID retryRequestId) {
        return jdbcTemplate.query(
                FIND_RETRY_APPLICATION_SQL,
                (rs, rowNumber) -> new PaymentRetryApplication(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("retry_request_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("previous_attempt_id", UUID.class),
                        rs.getObject("new_attempt_id", UUID.class),
                        rs.getObject("provider_evidence_id", UUID.class),
                        rs.getString("provider_id"),
                        rs.getTimestamp("requested_at").toInstant(),
                        rs.getTimestamp("applied_at").toInstant()),
                tenantId, retryRequestId
        ).stream().findFirst();
    }

    @Override
    public void insertRetryApplication(PaymentRetryApplication application) {
        jdbcTemplate.update("""
                INSERT INTO payment.retry_applications
                    (tenant_id, retry_request_id, payment_id, previous_attempt_id,
                     new_attempt_id, provider_evidence_id, provider_id, requested_at, applied_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, application.tenantId(), application.retryRequestId(),
                application.paymentId(), application.previousAttemptId(),
                application.newAttemptId(), application.providerEvidenceId(),
                application.providerId(), Timestamp.from(application.requestedAt()),
                Timestamp.from(application.appliedAt()));
    }

    @Override
    public Optional<PaymentAttempt> findAttemptById(
            UUID tenantId,
            PaymentId paymentId,
            UUID attemptId
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(attemptId, "Attempt ID must not be null");
        return jdbcTemplate.query(
                FIND_ATTEMPT_BY_ID_SQL,
                this::mapAttempt,
                tenantId,
                paymentId.value(),
                attemptId
        ).stream().findFirst();
    }

    @Override
    public Optional<AcceptedFinalProviderResult> findAcceptedFinalResult(
            UUID tenantId,
            PaymentId paymentId
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        return jdbcTemplate.query(
                FIND_ACCEPTED_FINAL_SQL,
                (rs, rowNumber) -> new AcceptedFinalProviderResult(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("attempt_id", UUID.class),
                        rs.getObject("provider_evidence_id", UUID.class),
                        rs.getObject("provider_result_id", UUID.class),
                        ProviderResultCategory.valueOf(rs.getString("final_category")),
                        rs.getString("provider_reference"),
                        rs.getTimestamp("applied_at").toInstant()
                ),
                tenantId,
                paymentId.value()
        ).stream().findFirst();
    }

    @Override
    public void insertAcceptedFinalResult(AcceptedFinalProviderResult result) {
        Objects.requireNonNull(result, "Accepted final result must not be null");
        jdbcTemplate.update(
                INSERT_ACCEPTED_FINAL_SQL,
                result.tenantId(),
                result.paymentId(),
                result.attemptId(),
                result.providerEvidenceId(),
                result.providerResultId(),
                result.finalCategory().name(),
                result.providerReference(),
                Timestamp.from(result.appliedAt())
        );
    }

    private PaymentAttempt mapAttempt(ResultSet rs, int rowNumber) throws SQLException {
        return new PaymentAttempt(
                PaymentAttemptId.from(rs.getObject("id", UUID.class)),
                rs.getObject("tenant_id", UUID.class),
                PaymentId.from(rs.getObject("payment_id", UUID.class)),
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

    private VersionedPayment mapVersionedPayment(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        UUID tenantId = resultSet.getObject("tenant_id", UUID.class);
        Payment payment = Payment.rehydrate(
                PaymentId.from(resultSet.getObject("id", UUID.class)),
                MerchantReference.from(
                        tenantId,
                        resultSet.getObject("merchant_id", UUID.class)
                ),
                CustomerId.from(resultSet.getObject("customer_id", UUID.class)),
                Money.of(
                        resultSet.getBigDecimal("amount"),
                        Currency.getInstance(resultSet.getString("currency"))
                ),
                PaymentMethodCategory.from(
                        resultSet.getString("payment_method_category")
                ),
                IdempotencyKey.from(resultSet.getString("idempotency_key")),
                PaymentStatus.valueOf(resultSet.getString("status"))
        );

        return new VersionedPayment(payment, resultSet.getLong("version"));
    }
}
