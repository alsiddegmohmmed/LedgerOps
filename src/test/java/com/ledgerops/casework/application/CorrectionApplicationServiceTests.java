package com.ledgerops.casework.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.casework.api.CorrectionRequestCommand;
import com.ledgerops.casework.api.CorrectionRequestSnapshot;
import com.ledgerops.casework.domain.CaseFile;
import com.ledgerops.casework.domain.CaseResolution;
import com.ledgerops.casework.domain.CaseSeverity;
import com.ledgerops.casework.domain.CaseSourceCategory;
import com.ledgerops.casework.domain.CorrectionRequest;
import com.ledgerops.casework.domain.CorrectionRequestStatus;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.SettlementCorrectionLedger;
import com.ledgerops.ledger.api.SettlementCorrectionLedgerError;
import com.ledgerops.ledger.api.SettlementCorrectionLedgerException;
import com.ledgerops.ledger.api.SettlementCorrectionRequest;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.reconciliation.api.ReconciliationCorrectionPort;
import com.ledgerops.reconciliation.api.SettlementCorrectionEligibility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrectionApplicationServiceTests {

    private static final Currency SAR = Currency.getInstance("SAR");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID DISCREPANCY = UUID.randomUUID();
    private static final UUID POSTING = UUID.randomUUID();
    private static final UUID ORIGINAL = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID CORRELATION = UUID.randomUUID();

    @Test
    void locksTheCorrectionBeforeLedgerAndRecordsRequestAndCompletionEvidence() {
        CaseStore cases = mock(CaseStore.class);
        CorrectionRequestStore corrections = mock(CorrectionRequestStore.class);
        ReconciliationCorrectionPort reconciliation = mock(ReconciliationCorrectionPort.class);
        SettlementCorrectionLedger ledger = mock(SettlementCorrectionLedger.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        CaseFile current = investigatingCase();
        AtomicReference<CorrectionRequest> inserted = new AtomicReference<>();

        when(reconciliation.lockAndCheck(TENANT, DISCREPANCY, POSTING, ORIGINAL))
                .thenReturn(eligibility());
        when(cases.lockByTenantAndId(TENANT, CASE_ID)).thenReturn(Optional.of(current));
        when(corrections.insertIfAbsent(any(CorrectionRequest.class))).thenAnswer(invocation -> {
            CorrectionRequest request = invocation.getArgument(0);
            inserted.set(request);
            return request;
        });
        when(corrections.lockByTenantAndId(eq(TENANT), any(UUID.class)))
                .thenAnswer(invocation -> Optional.of(inserted.get()));
        when(ledger.findCompensationForTarget(TENANT, ORIGINAL)).thenReturn(Optional.empty());
        AtomicReference<LedgerPostingEvidence> posted = new AtomicReference<>();
        when(ledger.postCompensation(any())).thenAnswer(invocation -> {
            SettlementCorrectionRequest request = invocation.getArgument(0);
            LedgerPostingEvidence evidence = new LedgerPostingEvidence(
                    UUID.randomUUID(), TENANT, "AUTHORISED_CORRECTION",
                    request.correctionId(), SAR, BigDecimal.TEN, BigDecimal.TEN,
                    List.of(), Optional.of(ORIGINAL));
            posted.set(evidence);
            return evidence;
        });

        CorrectionApplicationService service = new CorrectionApplicationService(
                cases, corrections, reconciliation, ledger, outbox, audit, fixedClock(),
                directTransactions(new AtomicInteger()));

        CorrectionRequestSnapshot result = service.request(command("Correct invalidated settlement"));

        assertEquals(CorrectionRequestStatus.COMPLETED, result.status());
        assertEquals(posted.get().transactionId(), result.compensationLedgerTransactionId());
        var order = inOrder(reconciliation, cases, corrections, ledger);
        order.verify(reconciliation).lockAndCheck(TENANT, DISCREPANCY, POSTING, ORIGINAL);
        order.verify(cases).lockByTenantAndId(TENANT, CASE_ID);
        order.verify(corrections).insertIfAbsent(any(CorrectionRequest.class));
        order.verify(corrections).lockByTenantAndId(eq(TENANT), any(UUID.class));
        order.verify(ledger).findCompensationForTarget(TENANT, ORIGINAL);
        order.verify(corrections).save(any(CorrectionRequest.class));
        order.verify(ledger).postCompensation(any());
        verify(audit).appendAction(
                eq("application-user"), eq(ACTOR.toString()), eq("HUMAN"), eq(TENANT),
                eq("case.correction-requested"), eq("correction"), anyString(),
                eq("Correct invalidated settlement"), anyString(), eq(CORRELATION.toString()));
        verify(audit).appendAction(
                eq("application-user"), eq(ACTOR.toString()), eq("HUMAN"), eq(TENANT),
                eq("case.correction-completed"), eq("correction"), anyString(),
                eq("Correct invalidated settlement"), anyString(), eq(CORRELATION.toString()));
    }

    @Test
    void rejectsChangedCorrectionContentForAnExistingTarget() {
        CaseStore cases = mock(CaseStore.class);
        CorrectionRequestStore corrections = mock(CorrectionRequestStore.class);
        ReconciliationCorrectionPort reconciliation = mock(ReconciliationCorrectionPort.class);
        SettlementCorrectionLedger ledger = mock(SettlementCorrectionLedger.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        AtomicReference<CorrectionRequest> candidate = new AtomicReference<>();
        CorrectionRequest existing = CorrectionRequest.request(
                UUID.randomUUID(), TENANT, CASE_ID, DISCREPANCY, POSTING, ORIGINAL,
                ACTOR, "Original reason", NOW);

        when(reconciliation.lockAndCheck(TENANT, DISCREPANCY, POSTING, ORIGINAL))
                .thenReturn(eligibility());
        when(cases.lockByTenantAndId(TENANT, CASE_ID)).thenReturn(Optional.of(investigatingCase()));
        when(corrections.insertIfAbsent(any(CorrectionRequest.class))).thenAnswer(invocation -> {
            candidate.set(invocation.getArgument(0));
            return existing;
        });
        when(corrections.lockByTenantAndId(TENANT, existing.correctionId()))
                .thenReturn(Optional.of(existing));

        CorrectionApplicationService service = new CorrectionApplicationService(
                cases, corrections, reconciliation, ledger, outbox, audit, fixedClock(),
                directTransactions(new AtomicInteger()));

        assertThrows(CorrectionRequestApplicationException.class,
                () -> service.request(command("Changed reason")));
        verify(ledger, never()).postCompensation(any());
        verify(corrections, never()).save(any(CorrectionRequest.class));
        assertEquals("Changed reason", candidate.get().reason());
    }

    @Test
    void recordsLedgerFailureOnlyAfterThePostingTransactionHasEnded() {
        CaseStore cases = mock(CaseStore.class);
        CorrectionRequestStore corrections = mock(CorrectionRequestStore.class);
        ReconciliationCorrectionPort reconciliation = mock(ReconciliationCorrectionPort.class);
        SettlementCorrectionLedger ledger = mock(SettlementCorrectionLedger.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        AtomicReference<CorrectionRequest> inserted = new AtomicReference<>();
        AtomicInteger transactionExecutions = new AtomicInteger();

        when(reconciliation.lockAndCheck(TENANT, DISCREPANCY, POSTING, ORIGINAL))
                .thenReturn(eligibility());
        when(cases.lockByTenantAndId(TENANT, CASE_ID))
                .thenReturn(Optional.of(investigatingCase()));
        when(corrections.insertIfAbsent(any(CorrectionRequest.class))).thenAnswer(invocation -> {
            CorrectionRequest request = invocation.getArgument(0);
            inserted.compareAndSet(null, request);
            return inserted.get();
        });
        when(corrections.lockByTenantAndId(eq(TENANT), any(UUID.class)))
                .thenAnswer(invocation -> Optional.of(inserted.get()));
        when(ledger.findCompensationForTarget(TENANT, ORIGINAL)).thenReturn(Optional.empty());
        when(ledger.postCompensation(any())).thenThrow(new SettlementCorrectionLedgerException(
                SettlementCorrectionLedgerError.POSTING_FAILED,
                "Ledger correction failpoint"));

        CorrectionApplicationService service = new CorrectionApplicationService(
                cases, corrections, reconciliation, ledger, outbox, audit, fixedClock(),
                directTransactions(transactionExecutions));

        CorrectionRequestSnapshot result = service.request(command("Correct invalidated settlement"));

        assertEquals(CorrectionRequestStatus.FAILED, result.status());
        assertEquals("Ledger correction failpoint", result.failureReason());
        assertEquals(2, transactionExecutions.get());
        ArgumentCaptor<CorrectionRequest> saved = ArgumentCaptor.forClass(CorrectionRequest.class);
        verify(corrections, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertTrue(saved.getAllValues().stream()
                .anyMatch(request -> request.status() == CorrectionRequestStatus.FAILED));
        verify(audit).appendAction(
                eq("application-user"), eq(ACTOR.toString()), eq("HUMAN"), eq(TENANT),
                eq("case.correction-failed"), eq("correction"), anyString(),
                eq("Ledger correction failpoint"), eq("{}"), eq(CORRELATION.toString()));
    }

    private static CaseFile investigatingCase() {
                return CaseFile.open(
                        CASE_ID, TENANT, CaseSourceCategory.RECONCILIATION_DISCREPANCY,
                        DISCREPANCY, null, CaseSeverity.HIGH, NOW.plusSeconds(3600), NOW)
                .transition(com.ledgerops.casework.domain.CaseStatus.INVESTIGATING,
                        ACTOR, "Start investigation", NOW.plusSeconds(1));
    }

    private static SettlementCorrectionEligibility eligibility() {
        return new SettlementCorrectionEligibility(
                TENANT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DISCREPANCY, POSTING, ORIGINAL, UUID.randomUUID(), "PAYMENT", UUID.randomUUID(),
                BigDecimal.TEN, SAR, NOW);
    }

    private static CorrectionRequestCommand command(String reason) {
        return new CorrectionRequestCommand(
                TENANT, CASE_ID, DISCREPANCY, POSTING, ORIGINAL, ACTOR,
                reason, CORRELATION, true);
    }

    private static TransactionOperations directTransactions(AtomicInteger executions) {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                executions.incrementAndGet();
                return action.doInTransaction(mock(TransactionStatus.class));
            }
        };
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
