package com.ledgerops.ledger.application;

import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.SettlementCorrectionLedgerError;
import com.ledgerops.ledger.api.SettlementCorrectionLedgerException;
import com.ledgerops.ledger.api.SettlementCorrectionRequest;
import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.ledger.domain.LedgerAccountId;
import com.ledgerops.ledger.domain.LedgerAccountReference;
import com.ledgerops.ledger.domain.LedgerAmount;
import com.ledgerops.ledger.domain.LedgerEntry;
import com.ledgerops.ledger.domain.LedgerSourceReference;
import com.ledgerops.ledger.domain.LedgerSourceType;
import com.ledgerops.ledger.domain.LedgerTransaction;
import com.ledgerops.ledger.domain.LedgerTransactionId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementCorrectionLedgerServiceTests {

    private static final Currency SAR = Currency.getInstance("SAR");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CORRECTION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Test
    void postsTheExactInverseOfAnUncompensatedSettlementAdjustment() {
        LedgerTransactionStore transactions = mock(LedgerTransactionStore.class);
        LedgerPostingService posting = mock(LedgerPostingService.class);
        LedgerTransaction original = settlementAdjustment();
        when(transactions.lockById(TENANT, original.id().value())).thenReturn(Optional.of(original));
        when(transactions.findBySource(TENANT, LedgerSourceType.AUTHORISED_CORRECTION, CORRECTION))
                .thenReturn(Optional.empty());
        when(transactions.findCompensationForTarget(TENANT, original.id().value()))
                .thenReturn(Optional.empty());
        when(posting.post(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SettlementCorrectionLedgerService service = new SettlementCorrectionLedgerService(
                transactions, posting, fixedClock());
        LedgerPostingEvidence evidence = service.postCompensation(
                new SettlementCorrectionRequest(TENANT, CORRECTION, original.id().value()));

        assertEquals(LedgerSourceType.AUTHORISED_CORRECTION.name(), evidence.sourceType());
        assertEquals(CORRECTION, evidence.sourceId());
        assertEquals(original.id().value(), evidence.compensatesTransactionId().orElseThrow());
        assertEquals("CREDIT", evidence.entries().getFirst().direction());
        assertEquals("DEBIT", evidence.entries().getLast().direction());
        assertEquals(original.totalDebits(), evidence.totalCredits());
        assertEquals(original.totalCredits(), evidence.totalDebits());
    }

    @Test
    void rejectsPaymentAndReversalTransactionsAsCorrectionTargets() {
        LedgerTransactionStore transactions = mock(LedgerTransactionStore.class);
        LedgerPostingService posting = mock(LedgerPostingService.class);
        LedgerTransaction payment = LedgerTransaction.post(
                LedgerTransactionId.newId(), TENANT,
                new LedgerSourceReference(TENANT, LedgerSourceType.PAYMENT, UUID.randomUUID()),
                NOW, entries());
        when(transactions.lockById(TENANT, payment.id().value())).thenReturn(Optional.of(payment));

        SettlementCorrectionLedgerService service = new SettlementCorrectionLedgerService(
                transactions, posting, fixedClock());

        SettlementCorrectionLedgerException exception = assertThrows(
                SettlementCorrectionLedgerException.class,
                () -> service.postCompensation(new SettlementCorrectionRequest(
                        TENANT, CORRECTION, payment.id().value())));

        assertEquals(
                SettlementCorrectionLedgerError.ORIGINAL_TRANSACTION_NOT_SETTLEMENT_ADJUSTMENT,
                exception.error());
    }

    @Test
    void rejectsASecondCorrectionForTheSameOriginalTransaction() {
        LedgerTransactionStore transactions = mock(LedgerTransactionStore.class);
        LedgerPostingService posting = mock(LedgerPostingService.class);
        LedgerTransaction original = settlementAdjustment();
        LedgerTransaction existing = LedgerTransaction.postCompensation(
                LedgerTransactionId.newId(), TENANT,
                new LedgerSourceReference(TENANT, LedgerSourceType.AUTHORISED_CORRECTION,
                        UUID.randomUUID()),
                original.id(), NOW, inverseEntries());
        when(transactions.lockById(TENANT, original.id().value())).thenReturn(Optional.of(original));
        when(transactions.findBySource(TENANT, LedgerSourceType.AUTHORISED_CORRECTION, CORRECTION))
                .thenReturn(Optional.empty());
        when(transactions.findCompensationForTarget(TENANT, original.id().value()))
                .thenReturn(Optional.of(existing));

        SettlementCorrectionLedgerService service = new SettlementCorrectionLedgerService(
                transactions, posting, fixedClock());

        SettlementCorrectionLedgerException exception = assertThrows(
                SettlementCorrectionLedgerException.class,
                () -> service.postCompensation(new SettlementCorrectionRequest(
                        TENANT, CORRECTION, original.id().value())));

        assertEquals(SettlementCorrectionLedgerError.TARGET_ALREADY_COMPENSATED, exception.error());
        assertTrue(exception.getMessage().contains("already has a compensation"));
    }

    private LedgerTransaction settlementAdjustment() {
        return LedgerTransaction.post(
                LedgerTransactionId.newId(), TENANT,
                new LedgerSourceReference(TENANT, LedgerSourceType.SETTLEMENT_ADJUSTMENT,
                        UUID.randomUUID()), NOW, entries());
    }

    private List<LedgerEntry> entries() {
        LedgerAccountReference debit = new LedgerAccountReference(
                TENANT, LedgerAccountId.newId(), AccountCode.SETTLEMENT_RECEIVABLE, SAR);
        LedgerAccountReference credit = new LedgerAccountReference(
                TENANT, LedgerAccountId.newId(), AccountCode.PROVIDER_CLEARING, SAR);
        LedgerAmount amount = LedgerAmount.of(new BigDecimal("10.00"), SAR);
        return List.of(LedgerEntry.debit(debit, amount), LedgerEntry.credit(credit, amount));
    }

    private List<LedgerEntry> inverseEntries() {
        List<LedgerEntry> original = entries();
        return List.of(
                LedgerEntry.credit(original.getFirst().account(), original.getFirst().amount()),
                LedgerEntry.debit(original.getLast().account(), original.getLast().amount())
        );
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
