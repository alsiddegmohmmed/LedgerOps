package com.ledgerops.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.UUID;

class LedgerTransactionTests {

    private static final Currency SAR = Currency.getInstance("SAR");
    private static final Instant POSTED_AT = Instant.parse("2026-07-20T19:00:00Z");

    @Test
    void postsAnImmutableBalancedTransactionWithDebitAndCreditEvidence() {
        UUID tenantId = UUID.randomUUID();
        List<LedgerEntry> mutableEntries = new ArrayList<>(List.of(
                debit(tenantId, "CUSTOMER_RECEIVABLE", "125.00", SAR),
                credit(tenantId, "MERCHANT_PAYABLE", "125.00", SAR)
        ));

        LedgerTransaction transaction = post(tenantId, mutableEntries);
        mutableEntries.add(debit(tenantId, "LATE_MUTATION", "1.00", SAR));

        assertEquals(2, transaction.entries().size());
        assertEquals(new BigDecimal("125.00"), transaction.totalDebits());
        assertEquals(new BigDecimal("125.00"), transaction.totalCredits());
        assertEquals(SAR, transaction.currency());
        assertEquals(POSTED_AT, transaction.postedAt());
        assertFalse(transaction.isCompensating());
        assertThrows(
                UnsupportedOperationException.class,
                () -> transaction.entries().add(
                        debit(tenantId, "MUTATION", "1.00", SAR)
                )
        );
    }

    @Test
    void rejectsFewerThanTwoEntries() {
        UUID tenantId = UUID.randomUUID();

        assertThrows(
                IllegalArgumentException.class,
                () -> post(
                        tenantId,
                        List.of(debit(tenantId, "ONLY_ENTRY", "10.00", SAR))
                )
        );
    }

    @Test
    void rejectsTransactionsWithoutBothDirections() {
        UUID tenantId = UUID.randomUUID();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> post(tenantId, List.of(
                        debit(tenantId, "FIRST", "5.00", SAR),
                        debit(tenantId, "SECOND", "5.00", SAR)
                ))
        );

        assertEquals(
                "Posted Ledger transaction must contain at least one debit and one credit",
                exception.getMessage()
        );
    }

    @Test
    void rejectsUnbalancedTransactions() {
        UUID tenantId = UUID.randomUUID();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> post(tenantId, List.of(
                        debit(tenantId, "RECEIVABLE", "10.00", SAR),
                        credit(tenantId, "PAYABLE", "9.99", SAR)
                ))
        );

        assertEquals(
                "Posted Ledger transaction must balance debits and credits",
                exception.getMessage()
        );
    }

    @Test
    void rejectsMixedCurrencyTransactions() {
        UUID tenantId = UUID.randomUUID();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> post(tenantId, List.of(
                        debit(tenantId, "SAR_ACCOUNT", "10.00", SAR),
                        credit(
                                tenantId,
                                "USD_ACCOUNT",
                                "10.00",
                                Currency.getInstance("USD")
                        )
                ))
        );

        assertEquals("Mixed-currency Ledger transactions are prohibited", exception.getMessage());
    }

    @Test
    void rejectsAnEntryWhoseCurrencyDiffersFromItsAccount() {
        UUID tenantId = UUID.randomUUID();
        LedgerAccountReference sarAccount = account(tenantId, "SAR_ACCOUNT", SAR);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> LedgerEntry.debit(
                        sarAccount,
                        amount("10.00", Currency.getInstance("USD"))
                )
        );

        assertEquals(
                "Ledger entry currency must match its account currency",
                exception.getMessage()
        );
    }

    @Test
    void rejectsCrossTenantAccountsAndSourceReferences() {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();

        assertThrows(
                IllegalArgumentException.class,
                () -> post(tenantId, List.of(
                        debit(otherTenantId, "FOREIGN", "10.00", SAR),
                        credit(tenantId, "LOCAL", "10.00", SAR)
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> LedgerTransaction.post(
                        LedgerTransactionId.newId(),
                        tenantId,
                        source(otherTenantId),
                        POSTED_AT,
                        List.of(
                                debit(tenantId, "DEBIT", "10.00", SAR),
                                credit(tenantId, "CREDIT", "10.00", SAR)
                        )
                )
        );
    }

    @Test
    void recordsCompensationAsANewTransactionReferencingTheOriginal() {
        UUID tenantId = UUID.randomUUID();
        LedgerTransactionId originalId = LedgerTransactionId.newId();
        LedgerTransaction compensation = LedgerTransaction.postCompensation(
                LedgerTransactionId.newId(),
                tenantId,
                new LedgerSourceReference(
                        tenantId,
                        LedgerSourceType.AUTHORISED_CORRECTION,
                        UUID.randomUUID()
                ),
                originalId,
                POSTED_AT,
                List.of(
                        debit(tenantId, "MERCHANT_PAYABLE", "25.00", SAR),
                        credit(tenantId, "CUSTOMER_RECEIVABLE", "25.00", SAR)
                )
        );

        assertTrue(compensation.isCompensating());
        assertEquals(originalId, compensation.compensatesTransactionId().orElseThrow());
        assertEquals(
                LedgerSourceType.AUTHORISED_CORRECTION,
                compensation.sourceReference().sourceType()
        );
    }

    @Test
    void rejectsSelfCompensation() {
        UUID tenantId = UUID.randomUUID();
        LedgerTransactionId transactionId = LedgerTransactionId.newId();

        assertThrows(
                IllegalArgumentException.class,
                () -> LedgerTransaction.postCompensation(
                        transactionId,
                        tenantId,
                        source(tenantId),
                        transactionId,
                        POSTED_AT,
                        List.of(
                                debit(tenantId, "DEBIT", "10.00", SAR),
                                credit(tenantId, "CREDIT", "10.00", SAR)
                        )
                )
        );
    }

    @Test
    void acceptsManyDeterministicallyGeneratedBalancedPostingShapes() {
        Random random = new Random(7_341_991L);

        for (int example = 0; example < 500; example++) {
            UUID tenantId = UUID.randomUUID();
            long totalMinorUnits = 4 + random.nextLong(999_997);
            long firstDebit = 1 + random.nextLong(totalMinorUnits - 1);
            long firstCredit = 1 + random.nextLong(totalMinorUnits - 1);
            long secondDebit = totalMinorUnits - firstDebit;
            long secondCredit = totalMinorUnits - firstCredit;

            LedgerTransaction transaction = post(tenantId, List.of(
                    debit(tenantId, "DEBIT_A", minorUnits(firstDebit), SAR),
                    debit(tenantId, "DEBIT_B", minorUnits(secondDebit), SAR),
                    credit(tenantId, "CREDIT_A", minorUnits(firstCredit), SAR),
                    credit(tenantId, "CREDIT_B", minorUnits(secondCredit), SAR)
            ));

            assertEquals(transaction.totalDebits(), transaction.totalCredits());
        }
    }

    @Test
    void exposesOnlyTheApprovedFinancialSourceCategories() {
        assertEquals(
                List.of(
                        LedgerSourceType.PAYMENT,
                        LedgerSourceType.REVERSAL,
                        LedgerSourceType.SETTLEMENT_ADJUSTMENT,
                        LedgerSourceType.AUTHORISED_CORRECTION
                ),
                List.of(LedgerSourceType.values())
        );
    }

    private LedgerTransaction post(UUID tenantId, List<LedgerEntry> entries) {
        return LedgerTransaction.post(
                LedgerTransactionId.newId(),
                tenantId,
                source(tenantId),
                POSTED_AT,
                entries
        );
    }

    private LedgerSourceReference source(UUID tenantId) {
        return new LedgerSourceReference(
                tenantId,
                LedgerSourceType.PAYMENT,
                UUID.randomUUID()
        );
    }

    private LedgerEntry debit(
            UUID tenantId,
            String code,
            String value,
            Currency currency
    ) {
        return LedgerEntry.debit(
                account(tenantId, code, currency),
                amount(value, currency)
        );
    }

    private LedgerEntry credit(
            UUID tenantId,
            String code,
            String value,
            Currency currency
    ) {
        return LedgerEntry.credit(
                account(tenantId, code, currency),
                amount(value, currency)
        );
    }

    private LedgerAccountReference account(
            UUID tenantId,
            String code,
            Currency currency
    ) {
        return new LedgerAccountReference(
                tenantId,
                LedgerAccountId.newId(),
                AccountCode.from(code),
                currency
        );
    }

    private LedgerAmount amount(String value, Currency currency) {
        return LedgerAmount.of(new BigDecimal(value), currency);
    }

    private String minorUnits(long value) {
        return BigDecimal.valueOf(value, 2).toPlainString();
    }
}
