package com.ledgerops.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.ledger.api.PaymentSuccessLedgerError;
import com.ledgerops.ledger.api.PaymentSuccessLedgerException;
import com.ledgerops.ledger.application.LedgerPostingService;
import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.ledger.domain.LedgerAccount;
import com.ledgerops.ledger.domain.LedgerAccountId;
import com.ledgerops.ledger.domain.LedgerAccountReference;
import com.ledgerops.ledger.domain.LedgerAccountRepository;
import com.ledgerops.ledger.domain.LedgerAmount;
import com.ledgerops.ledger.domain.LedgerEntry;
import com.ledgerops.ledger.domain.LedgerSourceReference;
import com.ledgerops.ledger.domain.LedgerSourceType;
import com.ledgerops.ledger.domain.LedgerTransaction;
import com.ledgerops.ledger.domain.LedgerTransactionId;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentCompletionIntegrationTests {

    private static final Currency SAR = Currency.getInstance("SAR");
    private static final Instant CREATED_AT = Instant.parse("2026-07-21T08:00:00Z");

    @Autowired
    private CompletePaymentAfterProviderSuccess completionUseCase;

    @Autowired
    private PaymentCreationStore creationStore;

    @Autowired
    private PaymentCompletionStore completionStore;

    @Autowired
    private PaymentLifecycleStore lifecycleStore;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private LedgerPostingService postingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void providerSuccessAtomicallyCompletesPaymentWithExactTwoEntryTemplate() {
        Payment payment = processingPayment("125.00");
        LedgerAccount providerClearing = insertAccount(
                payment.tenantId(),
                AccountCode.PROVIDER_CLEARING,
                SAR
        );
        LedgerAccount merchantPayable = insertAccount(
                payment.tenantId(),
                AccountCode.MERCHANT_PAYABLE,
                SAR
        );

        PaymentCompletionResult result = completionUseCase.complete(
                payment.tenantId(),
                payment.id()
        );

        assertEquals(PaymentStatus.COMPLETED, result.payment().payment().status());
        assertEquals(4, result.payment().version());
        assertFalse(result.replay());
        assertEquals(PaymentStatus.COMPLETED, load(payment).payment().status());
        assertEquals(1, postingCount(payment));
        assertEquals(
                List.of(
                        providerClearing.accountId().value(),
                        merchantPayable.accountId().value()
                ),
                entryAccountIds(payment)
        );
        assertEquals(List.of("DEBIT", "CREDIT"), entryDirections(payment));
        assertAmounts(payment, "125.00");
        assertEquals(List.of("SAR", "SAR"), entryCurrencies(payment));
        assertEquals(0, compensationCount(payment));
    }

    @Test
    void missingRequiredAccountRollsBackAndLeavesPaymentProcessing() {
        Payment payment = processingPayment("50.00");
        insertAccount(payment.tenantId(), AccountCode.PROVIDER_CLEARING, SAR);

        PaymentSuccessLedgerException exception = assertThrows(
                PaymentSuccessLedgerException.class,
                () -> completionUseCase.complete(payment.tenantId(), payment.id())
        );

        assertEquals(PaymentSuccessLedgerError.REQUIRED_ACCOUNT_NOT_FOUND, exception.error());
        assertProcessingWithoutPosting(payment);
    }

    @Test
    void accountsFromAnotherTenantCannotSatisfyPostingConfiguration() {
        Payment payment = processingPayment("50.00");
        UUID foreignTenant = UUID.randomUUID();
        insertAccount(foreignTenant, AccountCode.PROVIDER_CLEARING, SAR);
        insertAccount(foreignTenant, AccountCode.MERCHANT_PAYABLE, SAR);

        PaymentSuccessLedgerException exception = assertThrows(
                PaymentSuccessLedgerException.class,
                () -> completionUseCase.complete(payment.tenantId(), payment.id())
        );

        assertEquals(PaymentSuccessLedgerError.REQUIRED_ACCOUNT_NOT_FOUND, exception.error());
        assertProcessingWithoutPosting(payment);
    }

    @Test
    void ledgerEntryInsertionFailureRollsBackTransactionAndPayment() {
        Payment payment = configuredProcessingPayment("75.00");
        String suffix = payment.tenantId().toString().replace("-", "");
        String functionName = "ledger.fail_payment_entry_" + suffix;
        String triggerName = "fail_payment_entry_" + suffix;
        installLedgerEntryFailure(functionName, triggerName, payment.tenantId());

        try {
            assertThrows(
                    UncategorizedSQLException.class,
                    () -> completionUseCase.complete(payment.tenantId(), payment.id())
            );
        } finally {
            jdbcTemplate.execute("DROP TRIGGER " + triggerName + " ON ledger.entries");
            jdbcTemplate.execute("DROP FUNCTION " + functionName + "()");
        }

        assertProcessingWithoutPosting(payment);
    }

    @Test
    void paymentUpdateFailureRollsBackLedgerTransactionAndEntries() {
        Payment payment = configuredProcessingPayment("80.00");
        String suffix = payment.tenantId().toString().replace("-", "");
        String functionName = "payment.reject_completion_" + suffix;
        String triggerName = "reject_completion_" + suffix;
        installPaymentUpdateRejection(functionName, triggerName, payment.tenantId());

        try {
            assertThrows(
                    PaymentOptimisticConcurrencyException.class,
                    () -> completionUseCase.complete(payment.tenantId(), payment.id())
            );
        } finally {
            jdbcTemplate.execute("DROP TRIGGER " + triggerName + " ON payment.payments");
            jdbcTemplate.execute("DROP FUNCTION " + functionName + "()");
        }

        assertProcessingWithoutPosting(payment);
    }

    @Test
    void processingPaymentWithPreExistingPostingIsCriticalAndCreatesNoSecondPosting() {
        Payment payment = configuredProcessingPayment("90.00");
        post(payment, "90.00");

        PaymentCompletionConsistencyException exception = assertThrows(
                PaymentCompletionConsistencyException.class,
                () -> completionUseCase.complete(payment.tenantId(), payment.id())
        );

        assertEquals(
                PaymentCompletionConsistencyError.PROCESSING_WITH_EXISTING_POSTING,
                exception.error()
        );
        assertEquals(PaymentStatus.PROCESSING, load(payment).payment().status());
        assertEquals(1, postingCount(payment));
    }

    @Test
    void completedPaymentWithoutPostingIsCriticalAndDoesNotCreateLatePosting() {
        Payment payment = processingPayment("100.00");
        forceCompleted(payment);

        PaymentCompletionConsistencyException exception = assertThrows(
                PaymentCompletionConsistencyException.class,
                () -> completionUseCase.complete(payment.tenantId(), payment.id())
        );

        assertEquals(
                PaymentCompletionConsistencyError.COMPLETED_WITHOUT_POSTING,
                exception.error()
        );
        assertEquals(PaymentStatus.COMPLETED, load(payment).payment().status());
        assertEquals(0, postingCount(payment));
    }

    @Test
    void completedPaymentWithMismatchedPostingIsCriticalAndIsNotNormalized() {
        Payment payment = configuredProcessingPayment("110.00");
        UUID mismatchedTransactionId = post(payment, "109.00");
        forceCompleted(payment);

        PaymentCompletionConsistencyException exception = assertThrows(
                PaymentCompletionConsistencyException.class,
                () -> completionUseCase.complete(payment.tenantId(), payment.id())
        );

        assertEquals(
                PaymentCompletionConsistencyError.COMPLETED_WITH_MISMATCHED_POSTING,
                exception.error()
        );
        assertEquals(PaymentStatus.COMPLETED, load(payment).payment().status());
        assertEquals(1, postingCount(payment));
        assertAmounts(payment, "109.00");
        assertEquals(
                mismatchedTransactionId,
                sourceTransactionId(payment)
        );
    }

    @Test
    void validReplayReturnsOriginalPostingAndCreatesNoFinancialEffect() {
        Payment payment = configuredProcessingPayment("120.00");
        PaymentCompletionResult original = completionUseCase.complete(
                payment.tenantId(),
                payment.id()
        );

        PaymentCompletionResult replay = completionUseCase.complete(
                payment.tenantId(),
                payment.id()
        );

        assertTrue(replay.replay());
        assertEquals(original.ledgerTransactionId(), replay.ledgerTransactionId());
        assertEquals(original.payment().version(), replay.payment().version());
        assertEquals(1, postingCount(payment));
        assertEquals(2, entryCount(payment));
    }

    @Test
    void concurrentCompletionCreatesOneTransitionAndOnePosting() throws Exception {
        Payment payment = configuredProcessingPayment("130.00");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PaymentCompletionResult> first = executor.submit(() -> {
                barrier.await();
                return completionUseCase.complete(payment.tenantId(), payment.id());
            });
            Future<PaymentCompletionResult> second = executor.submit(() -> {
                barrier.await();
                return completionUseCase.complete(payment.tenantId(), payment.id());
            });

            List<PaymentCompletionResult> results = List.of(first.get(), second.get());

            assertEquals(1, results.stream().filter(PaymentCompletionResult::replay).count());
            assertEquals(1, results.stream().filter(result -> !result.replay()).count());
            assertEquals(
                    results.getFirst().ledgerTransactionId(),
                    results.get(1).ledgerTransactionId()
            );
            assertEquals(PaymentStatus.COMPLETED, load(payment).payment().status());
            assertEquals(4, load(payment).version());
            assertEquals(1, postingCount(payment));
            assertEquals(2, entryCount(payment));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void wrongTenantCannotFindOrCompletePayment() {
        Payment payment = configuredProcessingPayment("140.00");

        assertThrows(
                PaymentLifecycleNotFoundException.class,
                () -> completionUseCase.complete(UUID.randomUUID(), payment.id())
        );

        assertProcessingWithoutPosting(payment);
    }

    private Payment configuredProcessingPayment(String amount) {
        Payment payment = processingPayment(amount);
        insertAccount(payment.tenantId(), AccountCode.PROVIDER_CLEARING, SAR);
        insertAccount(payment.tenantId(), AccountCode.MERCHANT_PAYABLE, SAR);
        return payment;
    }

    private Payment processingPayment(String amount) {
        UUID tenantId = UUID.randomUUID();
        Payment payment = Payment.create(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal(amount), SAR),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("completion-integration-" + UUID.randomUUID())
        );
        creationStore.insertOrFind(payment, "d".repeat(64));

        Payment validating = payment.startValidation();
        assertTrue(completionStore.compareAndSet(validating, 0));
        Payment approved = validating.approve();
        assertTrue(completionStore.compareAndSet(approved, 1));
        Payment processing = approved.startProcessing();
        assertTrue(completionStore.compareAndSet(processing, 2));
        return processing;
    }

    private void forceCompleted(Payment payment) {
        VersionedPayment current = load(payment);
        assertEquals(PaymentStatus.PROCESSING, current.payment().status());
        assertTrue(completionStore.compareAndSet(current.payment().complete(), current.version()));
    }

    private LedgerAccount insertAccount(
            UUID tenantId,
            AccountCode code,
            Currency currency
    ) {
        LedgerAccount account = LedgerAccount.create(
                LedgerAccountId.newId(),
                tenantId,
                code,
                currency,
                CREATED_AT
        );
        accountRepository.insert(account);
        return account;
    }

    private UUID post(Payment payment, String amount) {
        LedgerAccount providerClearing = accountRepository.findByIdentity(
                payment.tenantId(),
                AccountCode.PROVIDER_CLEARING,
                payment.amount().currency()
        ).orElseThrow();
        LedgerAccount merchantPayable = accountRepository.findByIdentity(
                payment.tenantId(),
                AccountCode.MERCHANT_PAYABLE,
                payment.amount().currency()
        ).orElseThrow();
        LedgerTransaction transaction = LedgerTransaction.post(
                LedgerTransactionId.newId(),
                payment.tenantId(),
                new LedgerSourceReference(
                        payment.tenantId(),
                        LedgerSourceType.PAYMENT,
                        payment.id().value()
                ),
                CREATED_AT,
                List.of(
                        LedgerEntry.debit(reference(providerClearing), ledgerAmount(amount)),
                        LedgerEntry.credit(reference(merchantPayable), ledgerAmount(amount))
                )
        );
        postingService.post(transaction);
        return transaction.id().value();
    }

    private LedgerAccountReference reference(LedgerAccount account) {
        return new LedgerAccountReference(
                account.tenantId(),
                account.accountId(),
                account.accountCode(),
                account.currency()
        );
    }

    private LedgerAmount ledgerAmount(String amount) {
        return LedgerAmount.of(new BigDecimal(amount), SAR);
    }

    private VersionedPayment load(Payment payment) {
        return lifecycleStore.findByTenantAndId(payment.tenantId(), payment.id())
                .orElseThrow();
    }

    private int postingCount(Payment payment) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM ledger.transactions
                 WHERE tenant_id = ?
                   AND source_type = 'PAYMENT'
                   AND source_id = ?
                """,
                Integer.class,
                payment.tenantId(),
                payment.id().value()
        );
    }

    private int entryCount(Payment payment) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM ledger.entries entry
                  JOIN ledger.transactions transaction
                    ON transaction.tenant_id = entry.tenant_id
                   AND transaction.id = entry.transaction_id
                 WHERE transaction.tenant_id = ?
                   AND transaction.source_type = 'PAYMENT'
                   AND transaction.source_id = ?
                """,
                Integer.class,
                payment.tenantId(),
                payment.id().value()
        );
    }

    private List<UUID> entryAccountIds(Payment payment) {
        return queryEntryColumn(payment, "entry.account_id", UUID.class);
    }

    private List<String> entryDirections(Payment payment) {
        return queryEntryColumn(payment, "entry.direction", String.class);
    }

    private List<BigDecimal> entryAmounts(Payment payment) {
        return queryEntryColumn(payment, "entry.amount", BigDecimal.class);
    }

    private void assertAmounts(Payment payment, String expected) {
        BigDecimal amount = new BigDecimal(expected);
        List<BigDecimal> actual = entryAmounts(payment);
        assertEquals(2, actual.size());
        assertEquals(0, actual.getFirst().compareTo(amount));
        assertEquals(0, actual.get(1).compareTo(amount));
    }

    private List<String> entryCurrencies(Payment payment) {
        return queryEntryColumn(payment, "entry.currency", String.class);
    }

    private <T> List<T> queryEntryColumn(
            Payment payment,
            String column,
            Class<T> type
    ) {
        return jdbcTemplate.queryForList(
                """
                SELECT %s
                  FROM ledger.entries entry
                  JOIN ledger.transactions transaction
                    ON transaction.tenant_id = entry.tenant_id
                   AND transaction.id = entry.transaction_id
                 WHERE transaction.tenant_id = ?
                   AND transaction.source_type = 'PAYMENT'
                   AND transaction.source_id = ?
                 ORDER BY entry.entry_index
                """.formatted(column),
                type,
                payment.tenantId(),
                payment.id().value()
        );
    }

    private int compensationCount(Payment payment) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM ledger.transactions
                 WHERE tenant_id = ?
                   AND source_type = 'PAYMENT'
                   AND source_id = ?
                   AND compensates_transaction_id IS NOT NULL
                """,
                Integer.class,
                payment.tenantId(),
                payment.id().value()
        );
    }

    private UUID sourceTransactionId(Payment payment) {
        return jdbcTemplate.queryForObject(
                """
                SELECT id
                  FROM ledger.transactions
                 WHERE tenant_id = ?
                   AND source_type = 'PAYMENT'
                   AND source_id = ?
                """,
                UUID.class,
                payment.tenantId(),
                payment.id().value()
        );
    }

    private void assertProcessingWithoutPosting(Payment payment) {
        VersionedPayment persisted = load(payment);
        assertEquals(PaymentStatus.PROCESSING, persisted.payment().status());
        assertEquals(3, persisted.version());
        assertEquals(0, postingCount(payment));
        assertEquals(0, entryCount(payment));
    }

    private void installLedgerEntryFailure(
            String functionName,
            String triggerName,
            UUID tenantId
    ) {
        jdbcTemplate.execute(
                """
                CREATE FUNCTION %s()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF NEW.tenant_id = '%s'::uuid THEN
                        RAISE EXCEPTION 'Injected Ledger entry failure';
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """.formatted(functionName, tenantId)
        );
        jdbcTemplate.execute(
                """
                CREATE TRIGGER %s
                    BEFORE INSERT ON ledger.entries
                    FOR EACH ROW
                    EXECUTE FUNCTION %s()
                """.formatted(triggerName, functionName)
        );
    }

    private void installPaymentUpdateRejection(
            String functionName,
            String triggerName,
            UUID tenantId
    ) {
        jdbcTemplate.execute(
                """
                CREATE FUNCTION %s()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF OLD.tenant_id = '%s'::uuid AND NEW.status = 'COMPLETED' THEN
                        RETURN NULL;
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """.formatted(functionName, tenantId)
        );
        jdbcTemplate.execute(
                """
                CREATE TRIGGER %s
                    BEFORE UPDATE ON payment.payments
                    FOR EACH ROW
                    EXECUTE FUNCTION %s()
                """.formatted(triggerName, functionName)
        );
    }
}
