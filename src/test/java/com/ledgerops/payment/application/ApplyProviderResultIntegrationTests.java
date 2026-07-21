package com.ledgerops.payment.application;

import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.ledger.domain.LedgerAccount;
import com.ledgerops.ledger.domain.LedgerAccountId;
import com.ledgerops.ledger.domain.LedgerAccountRepository;
import com.ledgerops.ledger.api.PaymentSuccessLedgerException;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.messaging.api.IncomingMessage;
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
import com.ledgerops.provider.api.RetryDisposition;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ApplyProviderResultIntegrationTests {

    private static final Currency SAR = Currency.getInstance("SAR");
    private static final Instant ACCOUNT_CREATED_AT = Instant.parse("2026-07-21T08:00:00Z");

    @Autowired
    private ApplyProviderResult application;

    @Autowired
    private PaymentCreationStore creationStore;

    @Autowired
    private PaymentCompletionStore completionStore;

    @Autowired
    private PaymentSubmissionStore submissionStore;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void successCommitsInboxAcceptedEvidenceExactLedgerEffectAndLifecycleOutbox() {
        Fixture fixture = fixture(true);
        ObservedResult observed = evidence(fixture, ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE, "provider-success");

        PaymentProviderResultResult result = apply(observed, UUID.randomUUID());

        assertEquals(PaymentProviderResultOutcome.COMPLETED, result.outcome());
        assertEquals(PaymentStatus.COMPLETED, result.paymentStatus());
        assertNotNull(result.ledgerTransactionId());
        assertNotNull(result.lifecycleMessageId());
        assertEquals(PaymentStatus.COMPLETED, load(fixture.payment()).payment().status());
        assertEquals(1, inboxCount(fixture.payment()));
        assertEquals(1, acceptedCount(fixture.payment()));
        assertEquals(1, lifecycleOutboxCount(fixture.payment()));
        assertEquals("PaymentCompleted", lifecycleValue(fixture.payment(), "message_type"));
        assertEquals("payment-final:" + fixture.payment().id().value(),
                lifecycleValue(fixture.payment(), "deduplication_key"));
        assertEquals(1, postingCount(fixture.payment()));
        assertEquals(2, entryCount(fixture.payment()));
        assertEquals(List.of("PROVIDER_CLEARING", "MERCHANT_PAYABLE"),
                postingAccountCodes(fixture.payment()));
        assertEquals(List.of("DEBIT", "CREDIT"), postingDirections(fixture.payment()));
        assertTrue(postingAmounts(fixture.payment()).stream().allMatch(amount ->
                amount.compareTo(new BigDecimal("125.00")) == 0));
    }

    @ParameterizedTest
    @MethodSource("approvedFailureCategories")
    void definitiveFailureCommitsFailedStateAndPaymentFailedOutbox(
            ProviderResultCategory category
    ) {
        Fixture fixture = fixture(false);
        ObservedResult observed = evidence(
                fixture,
                category,
                RetryDisposition.NOT_RETRYABLE,
                "provider-failure"
        );

        PaymentProviderResultResult result = apply(observed, UUID.randomUUID());

        assertEquals(PaymentProviderResultOutcome.FAILED, result.outcome());
        assertEquals(PaymentStatus.FAILED, result.paymentStatus());
        assertEquals(PaymentStatus.FAILED, load(fixture.payment()).payment().status());
        assertEquals(category.name(), acceptedValue(fixture.payment(), "final_category"));
        assertEquals("PaymentFailed", lifecycleValue(fixture.payment(), "message_type"));
        assertTrue(lifecycleValue(fixture.payment(), "payload").contains(
                "\"finalCategory\":\"" + category + "\""
        ));
        assertEquals(0, postingCount(fixture.payment()));
    }

    @ParameterizedTest
    @MethodSource("nonFinalCategories")
    void nonFinalResultsLeavePaymentProcessingWithoutAcceptedOrLifecycleEvidence(
            ProviderResultCategory category,
            RetryDisposition disposition
    ) {
        Fixture fixture = fixture(false);
        ObservedResult observed = evidence(
                fixture,
                category,
                disposition,
                category.name().toLowerCase()
        );

        PaymentProviderResultResult result = apply(observed, UUID.randomUUID());

        assertEquals(PaymentProviderResultOutcome.NON_FINAL, result.outcome());
        assertEquals(PaymentStatus.PROCESSING, result.paymentStatus());
        assertEquals(PaymentStatus.PROCESSING, load(fixture.payment()).payment().status());
        assertEquals(1, inboxCount(fixture.payment()));
        assertEquals(0, acceptedCount(fixture.payment()));
        assertEquals(0, lifecycleOutboxCount(fixture.payment()));
        assertEquals(0, postingCount(fixture.payment()));
    }

    @Test
    void missingProviderEvidenceRollsBackInboxAndLeavesPaymentProcessing() {
        Fixture fixture = fixture(false);
        UUID missingEvidence = UUID.randomUUID();
        PaymentProviderResultCommand command = command(
                fixture,
                missingEvidence,
                UUID.randomUUID(),
                ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE,
                "missing",
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                UUID.randomUUID()
        );

        assertThrows(ProviderEvidenceUnavailableException.class, () -> application.apply(
                incoming(command), command
        ));

        assertNoStageBEffect(fixture.payment());
    }

    @Test
    void mismatchedProviderEvidenceIsPermanentAndRollsBackInbox() {
        Fixture fixture = fixture(false);
        ObservedResult observed = evidence(
                fixture,
                ProviderResultCategory.DECLINED,
                RetryDisposition.NOT_RETRYABLE,
                "declined"
        );
        PaymentProviderResultCommand mismatched = command(
                fixture,
                observed.evidenceId(),
                observed.providerResultId(),
                ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE,
                observed.providerReference(),
                observed.observedAt(),
                UUID.randomUUID()
        );

        assertThrows(PaymentProviderResultConsistencyException.class, () ->
                application.apply(incoming(mismatched), mismatched));

        assertNoStageBEffect(fixture.payment());
    }

    @Test
    void missingLedgerAccountRollsBackInboxAndLeavesPaymentProcessing() {
        Fixture fixture = fixture(false);
        insertAccount(fixture.payment().tenantId(), AccountCode.PROVIDER_CLEARING);
        ObservedResult observed = evidence(fixture, ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE, "provider-success");

        assertThrows(PaymentSuccessLedgerException.class,
                () -> apply(observed, UUID.randomUUID()));

        assertNoStageBEffect(fixture.payment());
    }

    @Test
    void ledgerInsertionFailureRollsBackEveryStageBEffect() {
        Fixture fixture = fixture(true);
        ObservedResult observed = evidence(fixture, ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE, "provider-success");
        String suffix = fixture.payment().tenantId().toString().replace("-", "");
        String function = "ledger.reject_result_entry_" + suffix;
        String trigger = "reject_result_entry_" + suffix;
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql "
                + "AS $$ BEGIN RAISE EXCEPTION 'forced entry failure'; END; $$");
        jdbc.execute("CREATE TRIGGER " + trigger
                + " BEFORE INSERT ON ledger.entries FOR EACH ROW EXECUTE FUNCTION "
                + function + "()");

        try {
            assertThrows(RuntimeException.class, () -> apply(observed, UUID.randomUUID()));
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON ledger.entries");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }

        assertNoStageBEffect(fixture.payment());
    }

    @Test
    void acceptedEvidenceInsertionFailureRollsBackCompletionAndInbox() {
        Fixture fixture = fixture(true);
        ObservedResult observed = evidence(fixture, ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE, "provider-success");
        String suffix = fixture.payment().tenantId().toString().replace("-", "");
        String function = "payment.reject_accepted_" + suffix;
        String trigger = "reject_accepted_" + suffix;
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql "
                + "AS $$ BEGIN RAISE EXCEPTION 'forced accepted evidence failure'; END; $$");
        jdbc.execute("CREATE TRIGGER " + trigger
                + " BEFORE INSERT ON payment.accepted_final_provider_results "
                + "FOR EACH ROW EXECUTE FUNCTION " + function + "()");

        try {
            assertThrows(RuntimeException.class, () -> apply(observed, UUID.randomUUID()));
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger
                    + " ON payment.accepted_final_provider_results");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }

        assertNoStageBEffect(fixture.payment());
    }

    @Test
    void lifecycleOutboxInsertionFailureRollsBackCompletionLedgerInboxAndAcceptedEvidence() {
        Fixture fixture = fixture(true);
        ObservedResult observed = evidence(fixture, ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE, "provider-success");
        String suffix = fixture.payment().tenantId().toString().replace("-", "");
        String function = "messaging.reject_lifecycle_" + suffix;
        String trigger = "reject_lifecycle_" + suffix;
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql "
                + "AS $$ BEGIN RAISE EXCEPTION 'forced lifecycle outbox failure'; END; $$");
        jdbc.execute("CREATE TRIGGER " + trigger
                + " BEFORE INSERT ON messaging.outbox FOR EACH ROW WHEN "
                + "(NEW.tenant_id = '" + fixture.payment().tenantId()
                + "' AND NEW.message_type = 'PaymentCompleted') EXECUTE FUNCTION "
                + function + "()");

        try {
            assertThrows(RuntimeException.class, () -> apply(observed, UUID.randomUUID()));
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON messaging.outbox");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }

        assertNoStageBEffect(fixture.payment());
    }

    @Test
    void paymentFailureUpdateErrorRollsBackInboxAndAcceptedEvidence() {
        Fixture fixture = fixture(false);
        ObservedResult observed = evidence(fixture, ProviderResultCategory.DECLINED,
                RetryDisposition.NOT_RETRYABLE, "declined");
        String suffix = fixture.payment().tenantId().toString().replace("-", "");
        String function = "payment.reject_result_" + suffix;
        String trigger = "reject_result_" + suffix;
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql "
                + "AS $$ BEGIN RETURN NULL; END; $$");
        jdbc.execute("CREATE TRIGGER " + trigger
                + " BEFORE UPDATE ON payment.payments FOR EACH ROW WHEN "
                + "(NEW.tenant_id = '" + fixture.payment().tenantId()
                + "') EXECUTE FUNCTION " + function + "()");

        try {
            assertThrows(PaymentOptimisticConcurrencyException.class,
                    () -> apply(observed, UUID.randomUUID()));
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON payment.payments");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }

        assertNoStageBEffect(fixture.payment());
    }

    @Test
    void duplicateMessageAndExactFinalReplayCreateNoSecondEffect() {
        Fixture fixture = fixture(true);
        ObservedResult observed = evidence(fixture, ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE, "provider-success");
        UUID firstMessageId = UUID.randomUUID();
        PaymentProviderResultResult first = apply(observed, firstMessageId);

        PaymentProviderResultResult duplicate = apply(observed, firstMessageId);
        PaymentProviderResultResult replay = apply(observed, UUID.randomUUID());

        assertEquals(PaymentProviderResultOutcome.DUPLICATE_MESSAGE, duplicate.outcome());
        assertEquals(PaymentProviderResultOutcome.REPLAY, replay.outcome());
        assertEquals(first.lifecycleMessageId(), replay.lifecycleMessageId());
        assertEquals(first.ledgerTransactionId(), replay.ledgerTransactionId());
        assertEquals(2, inboxCount(fixture.payment()));
        assertEquals(1, acceptedCount(fixture.payment()));
        assertEquals(1, lifecycleOutboxCount(fixture.payment()));
        assertEquals(1, postingCount(fixture.payment()));
        assertEquals(2, entryCount(fixture.payment()));
    }

    @Test
    void conflictingFinalResultDoesNotOverwriteAcceptedFinancialTruth() {
        Fixture fixture = fixture(true);
        ObservedResult success = evidence(fixture, ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE, "provider-success");
        apply(success, UUID.randomUUID());
        ObservedResult conflict = evidence(fixture, ProviderResultCategory.DECLINED,
                RetryDisposition.NOT_RETRYABLE, "provider-decline");

        assertThrows(PaymentProviderResultConsistencyException.class,
                () -> apply(conflict, UUID.randomUUID()));

        assertEquals(PaymentStatus.COMPLETED, load(fixture.payment()).payment().status());
        assertEquals("SUCCESS", acceptedValue(fixture.payment(), "final_category"));
        assertEquals(1, inboxCount(fixture.payment()));
        assertEquals(1, lifecycleOutboxCount(fixture.payment()));
        assertEquals(1, postingCount(fixture.payment()));
    }

    @Test
    void outOfOrderNonFinalResultAfterCompletionNeverRegressesPayment() {
        Fixture fixture = fixture(true);
        apply(evidence(fixture, ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE, "provider-success"), UUID.randomUUID());
        ObservedResult late = evidence(fixture, ProviderResultCategory.PENDING,
                RetryDisposition.STATUS_RECOVERY_REQUIRED, "provider-pending");

        PaymentProviderResultResult result = apply(late, UUID.randomUUID());

        assertEquals(PaymentProviderResultOutcome.TERMINAL_IGNORED, result.outcome());
        assertEquals(PaymentStatus.COMPLETED, load(fixture.payment()).payment().status());
        assertEquals(2, inboxCount(fixture.payment()));
        assertEquals(1, acceptedCount(fixture.payment()));
        assertEquals(1, lifecycleOutboxCount(fixture.payment()));
        assertEquals(1, postingCount(fixture.payment()));
    }

    @Test
    void concurrentFinalDeliveryCreatesOneTransitionAndOneFinancialEffect() throws Exception {
        Fixture fixture = fixture(true);
        ObservedResult observed = evidence(fixture, ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE, "provider-success");
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<PaymentProviderResultResult> first = executor.submit(() -> {
                barrier.await();
                return apply(observed, UUID.randomUUID());
            });
            Future<PaymentProviderResultResult> second = executor.submit(() -> {
                barrier.await();
                return apply(observed, UUID.randomUUID());
            });

            List<PaymentProviderResultResult> results = List.of(first.get(), second.get());
            assertEquals(1, results.stream().filter(result ->
                    result.outcome() == PaymentProviderResultOutcome.COMPLETED).count());
            assertEquals(1, results.stream().filter(result ->
                    result.outcome() == PaymentProviderResultOutcome.REPLAY).count());
            assertEquals(results.getFirst().lifecycleMessageId(),
                    results.get(1).lifecycleMessageId());
        }
        assertEquals(PaymentStatus.COMPLETED, load(fixture.payment()).payment().status());
        assertEquals(2, inboxCount(fixture.payment()));
        assertEquals(1, acceptedCount(fixture.payment()));
        assertEquals(1, lifecycleOutboxCount(fixture.payment()));
        assertEquals(1, postingCount(fixture.payment()));
    }

    @Test
    void acceptedFinalEvidenceCannotBeUpdatedOrDeleted() {
        Fixture fixture = fixture(false);
        apply(evidence(fixture, ProviderResultCategory.DECLINED,
                RetryDisposition.NOT_RETRYABLE, "declined"), UUID.randomUUID());

        assertThrows(Exception.class, () -> jdbc.update("""
                UPDATE payment.accepted_final_provider_results
                   SET final_category = 'PERMANENT_FAILURE'
                 WHERE tenant_id = ? AND payment_id = ?
                """, fixture.payment().tenantId(), fixture.payment().id().value()));
        assertThrows(Exception.class, () -> jdbc.update("""
                DELETE FROM payment.accepted_final_provider_results
                 WHERE tenant_id = ? AND payment_id = ?
                """, fixture.payment().tenantId(), fixture.payment().id().value()));
        assertEquals("DECLINED", acceptedValue(fixture.payment(), "final_category"));
    }

    @Test
    void wrongTenantCannotUseAnotherTenantsProviderEvidence() {
        Fixture fixture = fixture(false);
        ObservedResult observed = evidence(fixture, ProviderResultCategory.DECLINED,
                RetryDisposition.NOT_RETRYABLE, "declined");
        PaymentProviderResultCommand wrongTenant = new PaymentProviderResultCommand(
                UUID.randomUUID(), UUID.randomUUID(), fixture.payment().id().value(),
                fixture.attempt().attemptId().value(), observed.evidenceId(),
                observed.providerResultId(), "SIMULATOR",
                fixture.attempt().providerIdempotencyKey(), observed.category(),
                observed.disposition(), observed.providerReference(),
                "SUBMISSION_RESPONSE", observed.observedAt(), UUID.randomUUID()
        );

        assertThrows(ProviderEvidenceUnavailableException.class, () ->
                application.apply(incoming(wrongTenant), wrongTenant));

        assertNoStageBEffect(fixture.payment());
    }

    private static Stream<Arguments> approvedFailureCategories() {
        return Stream.of(
                Arguments.of(ProviderResultCategory.DECLINED),
                Arguments.of(ProviderResultCategory.PERMANENT_FAILURE)
        );
    }

    private static Stream<Arguments> nonFinalCategories() {
        return Stream.of(
                Arguments.of(ProviderResultCategory.ACCEPTED,
                        RetryDisposition.STATUS_RECOVERY_REQUIRED),
                Arguments.of(ProviderResultCategory.PENDING,
                        RetryDisposition.STATUS_RECOVERY_REQUIRED),
                Arguments.of(ProviderResultCategory.TEMPORARY_FAILURE,
                        RetryDisposition.SAFE_TO_RESUBMIT),
                Arguments.of(ProviderResultCategory.TEMPORARY_FAILURE,
                        RetryDisposition.STATUS_RECOVERY_REQUIRED),
                Arguments.of(ProviderResultCategory.UNKNOWN,
                        RetryDisposition.STATUS_RECOVERY_REQUIRED)
        );
    }

    private Fixture fixture(boolean accounts) {
        UUID tenantId = UUID.randomUUID();
        Payment payment = Payment.create(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("125.00"), SAR),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("provider-result-" + UUID.randomUUID())
        );
        creationStore.insertOrFind(payment, "a".repeat(64));
        Payment validating = payment.startValidation();
        assertTrue(completionStore.compareAndSet(validating, 0));
        Payment approved = validating.approve();
        assertTrue(completionStore.compareAndSet(approved, 1));
        Payment processing = approved.startProcessing();
        assertTrue(completionStore.compareAndSet(processing, 2));

        PaymentAttempt attempt = new PaymentAttempt(
                PaymentAttemptId.from(UUID.randomUUID()),
                tenantId,
                payment.id(),
                1,
                ProviderId.SIMULATOR,
                "payment:" + payment.id().value(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                payment.merchantReference().value(),
                payment.customerId(),
                payment.amount(),
                payment.paymentMethodCategory(),
                RequestIntentHash.calculate(payment)
        );
        submissionStore.insertAttempt(attempt);
        if (accounts) {
            insertAccount(tenantId, AccountCode.PROVIDER_CLEARING);
            insertAccount(tenantId, AccountCode.MERCHANT_PAYABLE);
        }
        return new Fixture(processing, attempt);
    }

    private ObservedResult evidence(
            Fixture fixture,
            ProviderResultCategory category,
            RetryDisposition disposition,
            String providerReference
    ) {
        UUID workId = existingWorkId(fixture);
        UUID interactionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID providerResultId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        boolean found = category != ProviderResultCategory.TEMPORARY_FAILURE
                || disposition != RetryDisposition.SAFE_TO_RESUBMIT;
        boolean noAcceptance = category == ProviderResultCategory.TEMPORARY_FAILURE
                && disposition == RetryDisposition.SAFE_TO_RESUBMIT;

        if (workId == null) {
            workId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO provider.work (
                    id, tenant_id, attempt_id, payment_id, work_type, status,
                    provider_id, provider_idempotency_key, request_intent_hash,
                    command_payload, due_at, correlation_id, causation_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'SUBMISSION', 'COMPLETED', 'SIMULATOR', ?, ?,
                          '{}', ?, ?, ?, ?, ?)
                """, workId, fixture.payment().tenantId(),
                fixture.attempt().attemptId().value(), fixture.payment().id().value(),
                fixture.attempt().providerIdempotencyKey(),
                    fixture.attempt().requestIntentHash(), Timestamp.from(observedAt),
                    UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(observedAt),
                    Timestamp.from(observedAt));
        }
        jdbc.update("""
                INSERT INTO provider.interactions (
                    interaction_id, tenant_id, work_id, attempt_id, payment_id,
                    provider_id, work_type, request_id, request_body_hash,
                    response_body_hash, http_status, communication_outcome,
                    latency_millis, started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, 'SIMULATOR', 'SUBMISSION', ?, ?, ?, 200,
                          'RESPONSE', 1, ?, ?)
                """, interactionId, fixture.payment().tenantId(), workId,
                fixture.attempt().attemptId().value(), fixture.payment().id().value(),
                requestId, "b".repeat(64), "c".repeat(64),
                Timestamp.from(observedAt.minusMillis(1)), Timestamp.from(observedAt));
        jdbc.update("""
                INSERT INTO provider.results (
                    evidence_id, tenant_id, interaction_id, work_id, attempt_id,
                    payment_id, provider_id, provider_idempotency_key,
                    provider_result_id, provider_reference, result_category,
                    retry_disposition, provider_transaction_found,
                    no_acceptance_proven, evidence_origin, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'SIMULATOR', ?, ?, ?, ?, ?, ?, ?,
                          'SUBMISSION_RESPONSE', ?)
                """, evidenceId, fixture.payment().tenantId(), interactionId, workId,
                fixture.attempt().attemptId().value(), fixture.payment().id().value(),
                fixture.attempt().providerIdempotencyKey(), providerResultId,
                providerReference, category.name(), disposition.name(), found,
                noAcceptance, Timestamp.from(observedAt));
        return new ObservedResult(
                evidenceId, providerResultId, category, disposition,
                providerReference, observedAt
        );
    }

    private UUID existingWorkId(Fixture fixture) {
        return jdbc.query("""
                SELECT id FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                fixture.payment().tenantId(), fixture.attempt().attemptId().value());
    }

    private PaymentProviderResultResult apply(ObservedResult observed, UUID messageId) {
        Fixture fixture = fixtureForEvidence(observed.evidenceId());
        PaymentProviderResultCommand command = command(
                fixture,
                observed.evidenceId(),
                observed.providerResultId(),
                observed.category(),
                observed.disposition(),
                observed.providerReference(),
                observed.observedAt(),
                messageId
        );
        return application.apply(incoming(command), command);
    }

    private Fixture fixtureForEvidence(UUID evidenceId) {
        return jdbc.query("""
                SELECT r.tenant_id, r.payment_id, r.attempt_id
                  FROM provider.results r WHERE r.evidence_id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new IllegalStateException("Provider evidence fixture is absent");
            }
            UUID tenantId = rs.getObject(1, UUID.class);
            PaymentId paymentId = PaymentId.from(rs.getObject(2, UUID.class));
            VersionedPayment payment = completionStore.lockByTenantAndId(tenantId, paymentId)
                    .orElseThrow();
            PaymentAttempt attempt = submissionStore.findAttempt(tenantId, paymentId, 1)
                    .orElseThrow();
            return new Fixture(payment.payment(), attempt);
        }, evidenceId);
    }

    private PaymentProviderResultCommand command(
            Fixture fixture,
            UUID evidenceId,
            UUID providerResultId,
            ProviderResultCategory category,
            RetryDisposition disposition,
            String providerReference,
            Instant observedAt,
            UUID messageId
    ) {
        return new PaymentProviderResultCommand(
                messageId,
                fixture.payment().tenantId(),
                fixture.payment().id().value(),
                fixture.attempt().attemptId().value(),
                evidenceId,
                providerResultId,
                "SIMULATOR",
                fixture.attempt().providerIdempotencyKey(),
                category,
                disposition,
                providerReference,
                "SUBMISSION_RESPONSE",
                observedAt,
                UUID.randomUUID()
        );
    }

    private IncomingMessage incoming(PaymentProviderResultCommand command) {
        return new IncomingMessage(
                ApplyProviderResult.CONSUMER_NAME,
                command.messageId(),
                command.tenantId(),
                "ProviderResultObserved"
        );
    }

    private void insertAccount(UUID tenantId, AccountCode code) {
        accountRepository.insert(LedgerAccount.create(
                LedgerAccountId.newId(), tenantId, code, SAR, ACCOUNT_CREATED_AT
        ));
    }

    private VersionedPayment load(Payment payment) {
        return completionStore.lockByTenantAndId(payment.tenantId(), payment.id())
                .orElseThrow();
    }

    private void assertNoStageBEffect(Payment payment) {
        assertEquals(PaymentStatus.PROCESSING, load(payment).payment().status());
        assertEquals(0, inboxCount(payment));
        assertEquals(0, acceptedCount(payment));
        assertEquals(0, lifecycleOutboxCount(payment));
        assertEquals(0, postingCount(payment));
    }

    private int inboxCount(Payment payment) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM messaging.inbox
                 WHERE consumer_name = ? AND tenant_id = ?
                """, Integer.class, ApplyProviderResult.CONSUMER_NAME, payment.tenantId());
    }

    private int acceptedCount(Payment payment) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM payment.accepted_final_provider_results
                 WHERE tenant_id = ? AND payment_id = ?
                """, Integer.class, payment.tenantId(), payment.id().value());
    }

    private String acceptedValue(Payment payment, String column) {
        return jdbc.queryForObject("SELECT " + column
                + " FROM payment.accepted_final_provider_results"
                + " WHERE tenant_id = ? AND payment_id = ?", String.class,
                payment.tenantId(), payment.id().value());
    }

    private int lifecycleOutboxCount(Payment payment) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM messaging.outbox
                 WHERE tenant_id = ? AND aggregate_id = ?
                   AND topic = 'ledgerops.payment.lifecycle.v1'
                """, Integer.class, payment.tenantId(), payment.id().value());
    }

    private String lifecycleValue(Payment payment, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM messaging.outbox"
                + " WHERE tenant_id = ? AND aggregate_id = ?"
                + " AND topic = 'ledgerops.payment.lifecycle.v1'", String.class,
                payment.tenantId(), payment.id().value());
    }

    private int postingCount(Payment payment) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM ledger.transactions
                 WHERE tenant_id = ? AND source_type = 'PAYMENT' AND source_id = ?
                """, Integer.class, payment.tenantId(), payment.id().value());
    }

    private int entryCount(Payment payment) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM ledger.entries e
                JOIN ledger.transactions t ON t.id = e.transaction_id
                 WHERE t.tenant_id = ? AND t.source_type = 'PAYMENT' AND t.source_id = ?
                """, Integer.class, payment.tenantId(), payment.id().value());
    }

    private List<String> postingAccountCodes(Payment payment) {
        return jdbc.queryForList("""
                SELECT a.account_code FROM ledger.entries e
                JOIN ledger.transactions t ON t.id = e.transaction_id
                JOIN ledger.accounts a ON a.id = e.account_id
                 WHERE t.tenant_id = ? AND t.source_type = 'PAYMENT' AND t.source_id = ?
                 ORDER BY CASE e.direction WHEN 'DEBIT' THEN 1 ELSE 2 END
                """, String.class, payment.tenantId(), payment.id().value());
    }

    private List<String> postingDirections(Payment payment) {
        return jdbc.queryForList("""
                SELECT e.direction FROM ledger.entries e
                JOIN ledger.transactions t ON t.id = e.transaction_id
                 WHERE t.tenant_id = ? AND t.source_type = 'PAYMENT' AND t.source_id = ?
                 ORDER BY CASE e.direction WHEN 'DEBIT' THEN 1 ELSE 2 END
                """, String.class, payment.tenantId(), payment.id().value());
    }

    private List<BigDecimal> postingAmounts(Payment payment) {
        return jdbc.queryForList("""
                SELECT e.amount FROM ledger.entries e
                JOIN ledger.transactions t ON t.id = e.transaction_id
                 WHERE t.tenant_id = ? AND t.source_type = 'PAYMENT' AND t.source_id = ?
                 ORDER BY CASE e.direction WHEN 'DEBIT' THEN 1 ELSE 2 END
                """, BigDecimal.class, payment.tenantId(), payment.id().value());
    }

    private record Fixture(Payment payment, PaymentAttempt attempt) {
    }

    private record ObservedResult(
            UUID evidenceId,
            UUID providerResultId,
            ProviderResultCategory category,
            RetryDisposition disposition,
            String providerReference,
            Instant observedAt
    ) {
    }
}
