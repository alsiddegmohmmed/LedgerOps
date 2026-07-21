package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import com.ledgerops.provider.application.ProviderCallResult;
import com.ledgerops.provider.application.ProviderExecutionStore;
import com.ledgerops.provider.application.ProviderWorkClaim;
import com.ledgerops.provider.application.ProviderWorkConsistencyException;
import com.ledgerops.provider.application.ProviderWorkType;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ProviderExecutionPersistenceIntegrationTests {
    @Autowired ProviderExecutionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void dueSafeRetryCreatesOneStableRetryRequestAndCommandWithoutAnotherProviderCall() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim submission = store.claimNext("safe-retry-worker").orElseThrow();
        store.record(submission, result(submission, UUID.randomUUID(),
                ProviderResultCategory.TEMPORARY_FAILURE,
                RetryDisposition.SAFE_TO_RESUBMIT, false, true));

        assertEquals("WAITING_RETRY_REQUEST", workStatus(tenantId, attemptId, "SUBMISSION"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT execution_count FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                """, Integer.class, tenantId, attemptId));
        jdbc.update("""
                UPDATE provider.work SET due_at = now()
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                """, tenantId, attemptId);

        var retryClaim = store.claimRetryRequest("retry-request-worker").orElseThrow();
        store.issueRetryRequest(retryClaim);

        UUID retryRequestId = jdbc.queryForObject("""
                SELECT retry_request_id FROM provider.retry_requests
                 WHERE tenant_id = ? AND previous_attempt_id = ?
                """, UUID.class, tenantId, attemptId);
        assertEquals("COMPLETED", workStatus(tenantId, attemptId, "SUBMISSION"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.outbox
                 WHERE producer_name = 'provider' AND deduplication_key = ?
                   AND message_type = 'PaymentSubmissionRetryRequested'
                """, Integer.class, "payment-retry:" + retryRequestId));
        assertTrue(store.claimRetryRequest("second-worker").isEmpty());
        assertEquals(1, jdbc.queryForObject("""
                SELECT execution_count FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                """, Integer.class, tenantId, attemptId));
        assertThrows(Exception.class, () -> jdbc.update("""
                DELETE FROM provider.retry_requests
                 WHERE tenant_id = ? AND retry_request_id = ?
                """, tenantId, retryRequestId));
        assertThrows(Exception.class, () -> jdbc.update("""
                INSERT INTO provider.retry_requests
                    (retry_request_id, tenant_id, payment_id, previous_attempt_id,
                     provider_evidence_id, provider_id, requested_at)
                VALUES (?, ?, ?, ?, ?, 'SIMULATOR', now())
                """, UUID.randomUUID(), tenantId, paymentId, attemptId,
                retryClaim.providerEvidenceId()));
    }

    @Test
    void preTransmissionRetryIsFencedBoundedAndDoesNotConsumeAProviderAttempt() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim first = store.claimNext("transport-worker").orElseThrow();
        assertTrue(first.preTransmissionRetryAvailable());

        store.defer(first, "PRETRANSMISSION_FAILURE");
        assertEquals(1, jdbc.queryForObject("""
                SELECT transport_retry_count FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                """, Integer.class, tenantId, attemptId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT execution_count FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                """, Integer.class, tenantId, attemptId));
        jdbc.update("""
                UPDATE provider.work SET due_at = now()
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                """, tenantId, attemptId);
        ProviderWorkClaim second = store.claimNext("transport-worker").orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(second.preTransmissionRetryAvailable());
        assertThrows(ProviderWorkConsistencyException.class,
                () -> store.defer(second, "PRETRANSMISSION_FAILURE"));
        store.markUnresolved(second, "PRETRANSMISSION_RETRY_EXHAUSTED");
    }

    @Test
    void statusRecoveryCreatesOnlyStatusQueryWorkAndRetriesOnlyThatWorkType() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim submission = store.claimNext("submission-worker").orElseThrow();
        store.record(submission, result(submission, UUID.randomUUID(),
                ProviderResultCategory.UNKNOWN,
                RetryDisposition.STATUS_RECOVERY_REQUIRED, false, false));

        assertEquals("WAITING_STATUS", workStatus(tenantId, attemptId, "SUBMISSION"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'STATUS_QUERY'
                """, Integer.class, tenantId, attemptId));
        jdbc.update("""
                UPDATE provider.work SET due_at = now()
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'STATUS_QUERY'
                """, tenantId, attemptId);
        ProviderWorkClaim status = store.claimNext("status-worker").orElseThrow();
        assertEquals(ProviderWorkType.STATUS_QUERY, status.workType());
        store.record(status, result(status, UUID.randomUUID(),
                ProviderResultCategory.PENDING,
                RetryDisposition.STATUS_RECOVERY_REQUIRED, true, false));

        assertEquals("WAITING_STATUS", workStatus(tenantId, attemptId, "SUBMISSION"));
        assertEquals("WAITING_STATUS", workStatus(tenantId, attemptId, "STATUS_QUERY"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT execution_count FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                """, Integer.class, tenantId, attemptId));
    }

    @Test
    void fencedResultTransactionPersistsImmutableEvidenceOutboxAndCompletionTogether() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim claim = store.claimNext("worker-a").orElseThrow();
        UUID resultId = UUID.randomUUID();

        store.record(claim, result(claim, resultId, ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE, true, false));

        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM provider.work WHERE id = ?", String.class, claim.workId()));
        assertEquals(1, count("provider.results", "provider_result_id", resultId));
        assertEquals(1, count("provider.interactions", "work_id", claim.workId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.outbox
                 WHERE producer_name = 'provider'
                   AND deduplication_key = ?
                   AND message_type = 'ProviderResultObserved'
                """, Integer.class,
                "provider-result:" + tenantId + ":SIMULATOR:" + resultId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM provider.work
                 WHERE id = ? AND lease_token IS NOT NULL
                """, Integer.class, claim.workId()));
    }

    @Test
    void staleLeaseCannotPersistEvidenceOrMutateReclaimedWork() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim claim = store.claimNext("worker-a").orElseThrow();
        jdbc.update("UPDATE provider.work SET lease_token = ? WHERE id = ?",
                UUID.randomUUID(), claim.workId());

        assertThrows(ProviderWorkConsistencyException.class, () -> store.record(
                claim, result(claim, UUID.randomUUID(), ProviderResultCategory.SUCCESS,
                        RetryDisposition.NOT_RETRYABLE, true, false)));
        assertEquals(0, count("provider.results", "work_id", claim.workId()));
        assertEquals(0, count("provider.interactions", "work_id", claim.workId()));
        assertEquals("CLAIMED", jdbc.queryForObject(
                "SELECT status FROM provider.work WHERE id = ?", String.class, claim.workId()));
    }

    @Test
    void safeTemporaryFailureWaitsForIntentionalRetryRequestAndNeverBecomesRetryable() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim claim = store.claimNext("worker-a").orElseThrow();

        store.record(claim, result(claim, UUID.randomUUID(),
                ProviderResultCategory.TEMPORARY_FAILURE,
                RetryDisposition.SAFE_TO_RESUBMIT, false, true));

        assertEquals("WAITING_RETRY_REQUEST", jdbc.queryForObject(
                "SELECT status FROM provider.work WHERE id = ?", String.class, claim.workId()));
        assertTrue(store.claimNext("worker-b").isEmpty(),
                "SUBMISSION work awaiting a new Payment Attempt must not be called again");
    }

    @Test
    void databaseRejectsSafeRetryWithoutProofOfNoAcceptance() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim claim = store.claimNext("worker-a").orElseThrow();

        assertThrows(RuntimeException.class, () -> store.record(claim,
                result(claim, UUID.randomUUID(), ProviderResultCategory.TEMPORARY_FAILURE,
                        RetryDisposition.SAFE_TO_RESUBMIT, true, false)));
        assertEquals(0, count("provider.results", "work_id", claim.workId()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.outbox
                 WHERE aggregate_id = ? AND message_type = 'ProviderResultObserved'
                """, Integer.class, paymentId));
    }

    @Test
    void duplicateProviderResultKeepsBothInteractionsButOneLogicalResultAndOutbox() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim submission = store.claimNext("worker-a").orElseThrow();
        store.record(submission, result(submission, resultId,
                ProviderResultCategory.SUCCESS, RetryDisposition.NOT_RETRYABLE, true, false));
        insertStatusWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim status = store.claimNext("worker-b").orElseThrow();
        store.record(status, result(status, resultId,
                ProviderResultCategory.SUCCESS, RetryDisposition.NOT_RETRYABLE, true, false));

        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM provider.interactions
                 WHERE tenant_id = ? AND attempt_id = ?
                """, Integer.class, tenantId, attemptId));
        assertEquals(1, count("provider.results", "provider_result_id", resultId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.outbox
                 WHERE deduplication_key = ?
                """, Integer.class,
                "provider-result:" + tenantId + ":SIMULATOR:" + resultId));
    }

    @Test
    void expiredPossiblyTransmittedSubmissionBecomesRecoveryOnlyAndIsNotCountedAgain() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim abandoned = store.claimNext("crashed-worker").orElseThrow();
        jdbc.update("""
                UPDATE provider.work SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE id = ?
                """, abandoned.workId());

        ProviderWorkClaim recovered = store.claimNext("recovery-worker").orElseThrow();
        assertTrue(recovered.recoveryOnly());
        assertEquals(1, jdbc.queryForObject(
                "SELECT execution_count FROM provider.work WHERE id = ?",
                Integer.class, recovered.workId()));
        store.markAmbiguous(recovered, "CRASH_AFTER_POSSIBLE_SUBMISSION");
        assertEquals("WAITING_STATUS", jdbc.queryForObject(
                "SELECT status FROM provider.work WHERE id = ?", String.class,
                recovered.workId()));
        assertEquals(1, count("provider.operational_events", "work_id", recovered.workId()));
        assertEquals(0, count("provider.interactions", "work_id", recovered.workId()));
        assertEquals(0, count("provider.results", "work_id", recovered.workId()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.outbox
                 WHERE aggregate_id = ? AND message_type = 'ProviderResultObserved'
                """, Integer.class, paymentId));
    }

    @Test
    void twelfthCompletedStatusQueryExhaustsWithoutAThirteenthProviderCall() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertStatusWork(tenantId, paymentId, attemptId);
        jdbc.update("""
                UPDATE provider.work SET due_at = now()
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'STATUS_QUERY'
                """, tenantId, attemptId);
        jdbc.update("""
                UPDATE provider.work SET execution_count = 12
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'STATUS_QUERY'
                """, tenantId, attemptId);

        ProviderWorkClaim exhausted = store.claimNext("recovery-worker").orElseThrow();
        assertTrue(exhausted.exhausted());
        assertEquals(12, jdbc.queryForObject(
                "SELECT execution_count FROM provider.work WHERE id = ?",
                Integer.class, exhausted.workId()));
        store.markUnresolved(exhausted, "STATUS_RECOVERY_EXHAUSTED");
        assertEquals("UNRESOLVED", jdbc.queryForObject(
                "SELECT status FROM provider.work WHERE id = ?", String.class,
                exhausted.workId()));
    }

    @Test
    void statusQuerySettlementClosesTheOriginatingSubmissionRecoveryOwner() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim submission = store.claimNext("submission-worker").orElseThrow();
        store.record(submission, result(submission, UUID.randomUUID(),
                ProviderResultCategory.ACCEPTED,
                RetryDisposition.STATUS_RECOVERY_REQUIRED, true, false));
        jdbc.update("""
                UPDATE provider.work SET due_at = now()
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'STATUS_QUERY'
                """, tenantId, attemptId);
        ProviderWorkClaim status = store.claimNext("status-worker").orElseThrow();
        store.record(status, result(status, UUID.randomUUID(),
                ProviderResultCategory.SUCCESS, RetryDisposition.NOT_RETRYABLE, true, false));

        assertEquals("COMPLETED", jdbc.queryForObject("""
                SELECT status FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                """, String.class, tenantId, attemptId));
        assertEquals("COMPLETED", jdbc.queryForObject("""
                SELECT status FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'STATUS_QUERY'
                """, String.class, tenantId, attemptId));
    }

    @Test
    void providerWorkAndEvidenceCannotCrossTenantsOrBeDeleted() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim claim = store.claimNext("worker-a").orElseThrow();

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO provider.interactions
                            (interaction_id, tenant_id, work_id, attempt_id, payment_id,
                             provider_id, work_type, request_id, request_body_hash,
                             communication_outcome, latency_millis, started_at, completed_at)
                        VALUES (?, ?, ?, ?, ?, 'SIMULATOR', 'SUBMISSION', ?, ?,
                                'RESPONSE', 1, ?, ?)
                        """, UUID.randomUUID(), UUID.randomUUID(), claim.workId(), attemptId,
                        paymentId, UUID.randomUUID(), "a".repeat(64),
                        Timestamp.from(Instant.now()), Timestamp.from(Instant.now())));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("DELETE FROM provider.work WHERE id = ?", claim.workId()));
        assertEquals(1, count("provider.work", "id", claim.workId()));
    }

    @Test
    void leaseRenewalRequiresTheCurrentTokenAndExtendsOnlyThatClaim() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim claim = store.claimNext("worker-a").orElseThrow();
        Instant before = jdbc.queryForObject(
                "SELECT lease_expires_at FROM provider.work WHERE id = ?",
                Timestamp.class, claim.workId()).toInstant();

        assertTrue(store.renew(claim.workId(), claim.leaseToken()));
        Instant after = jdbc.queryForObject(
                "SELECT lease_expires_at FROM provider.work WHERE id = ?",
                Timestamp.class, claim.workId()).toInstant();
        assertTrue(!after.isBefore(before));
        org.junit.jupiter.api.Assertions.assertFalse(
                store.renew(claim.workId(), UUID.randomUUID()));
    }

    @Test
    void realCoreTransactionPreventsProviderNetworkExecution() throws Exception {
        try (SimulatorProviderGateway gateway = new SimulatorProviderGateway(
                java.net.URI.create("http://127.0.0.1:1"),
                new ProviderHmacSigner("core-key", "test-only-shared-secret"),
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("tx-test"),
                io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("tx-test"),
                java.time.Clock.systemUTC())) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    assertThrows(IllegalStateException.class,
                            () -> gateway.execute(new ProviderWorkClaim(
                                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                    UUID.randomUUID(), 1,
                                    com.ledgerops.provider.application.ProviderWorkType.SUBMISSION,
                                    "SIMULATOR", "payment:" + UUID.randomUUID(), "a".repeat(64),
                                    "{}", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                    Instant.now().plusSeconds(30), true, false, false))));
        }
    }

    @Test
    void postgresRejectsAResultDispositionOutsideTheApprovedMatrix() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        insertWork(tenantId, paymentId, attemptId);
        ProviderWorkClaim claim = store.claimNext("worker-a").orElseThrow();
        UUID interactionId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO provider.interactions
                    (interaction_id, tenant_id, work_id, attempt_id, payment_id,
                     provider_id, work_type, request_id, request_body_hash,
                     communication_outcome, latency_millis, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, 'SIMULATOR', 'SUBMISSION', ?, ?,
                        'RESPONSE', 1, ?, ?)
                """, interactionId, tenantId, claim.workId(), attemptId, paymentId,
                UUID.randomUUID(), "a".repeat(64), Timestamp.from(now), Timestamp.from(now));

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO provider.results
                            (evidence_id, tenant_id, interaction_id, work_id, attempt_id,
                             payment_id, provider_id, provider_idempotency_key,
                             provider_result_id, result_category, retry_disposition,
                             provider_transaction_found, no_acceptance_proven,
                             evidence_origin, observed_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'SIMULATOR', ?, ?, 'SUCCESS',
                                'STATUS_RECOVERY_REQUIRED', true, false,
                                'SUBMISSION_RESPONSE', ?)
                        """, UUID.randomUUID(), tenantId, interactionId, claim.workId(),
                        attemptId, paymentId, claim.providerIdempotencyKey(), UUID.randomUUID(),
                        Timestamp.from(now)));
    }

    private ProviderCallResult result(ProviderWorkClaim claim, UUID resultId,
            ProviderResultCategory category, RetryDisposition disposition,
            boolean found, boolean noAcceptance) {
        Instant now = Instant.now();
        return new ProviderCallResult(UUID.randomUUID(), resultId,
                found ? "SIM-REFERENCE" : null, category, disposition, found,
                noAcceptance, 200, "a".repeat(64), "b".repeat(64), "RESPONSE",
                25, null, now.minusMillis(25), now);
    }

    private void insertWork(UUID tenantId, UUID paymentId, UUID attemptId) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO provider.work
                    (id, tenant_id, attempt_id, payment_id, attempt_sequence, work_type, status,
                     provider_id, provider_idempotency_key, request_intent_hash,
                     command_payload, due_at, correlation_id, causation_id,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'SUBMISSION', 'PENDING', 'SIMULATOR', ?, ?, ?,
                        ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, attemptId, paymentId,
                "payment:" + paymentId, "c".repeat(64), payload(attemptId, paymentId),
                Timestamp.from(now), UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.from(now), Timestamp.from(now));
    }

    private void insertStatusWork(UUID tenantId, UUID paymentId, UUID attemptId) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO provider.work
                    (id, tenant_id, attempt_id, payment_id, attempt_sequence, work_type, status,
                     provider_id, provider_idempotency_key, request_intent_hash,
                     command_payload, due_at, correlation_id, causation_id,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'STATUS_QUERY', 'PENDING', 'SIMULATOR', ?, ?, ?,
                        ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, attemptId, paymentId,
                "payment:" + paymentId, "c".repeat(64), payload(attemptId, paymentId),
                Timestamp.from(now), UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.from(now), Timestamp.from(now));
    }

    private String payload(UUID attemptId, UUID paymentId) {
        return "{\"attemptId\":\"" + attemptId + "\",\"paymentId\":\"" + paymentId
                + "\",\"providerIdempotencyKey\":\"payment:" + paymentId
                + "\",\"requestIntentHash\":\"" + "c".repeat(64) + "\"}";
    }

    private String workStatus(UUID tenantId, UUID attemptId, String workType) {
        return jdbc.queryForObject("""
                SELECT status FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = ?
                """, String.class, tenantId, attemptId, workType);
    }

    private int count(String table, String column, UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, id);
    }
}
