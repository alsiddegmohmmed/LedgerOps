package com.ledgerops.reconciliation.application;

import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.SettlementLedger;
import com.ledgerops.ledger.api.SettlementPostingRequest;
import com.ledgerops.ledger.api.SettlementPostingType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReconciliationSettlementPostingService {

    public static final String TEMPLATE_VERSION = "release-0.3-settlement-v1";

    private final ReconciliationSnapshotStore snapshots;
    private final SettlementPostingStore postings;
    private final SettlementLedger ledger;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public ReconciliationSettlementPostingService(
            ReconciliationSnapshotStore snapshots,
            SettlementPostingStore postings,
            SettlementLedger ledger,
            Clock clock,
            DataSource dataSource
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "Snapshot store must not be null");
        this.postings = Objects.requireNonNull(postings, "Posting store must not be null");
        this.ledger = Objects.requireNonNull(ledger, "Settlement Ledger must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(dataSource, "Data source must not be null")));
    }

    public List<SettlementPostingStore.PostingWork> prepareCurrentRun(PrepareCommand command) {
        ReconciliationSnapshotStore.CurrentRun current = requireCurrent(command.tenantId(), command.batchFamilyId());
        if (!current.runId().equals(command.runId())) {
            throw new IllegalStateException("Only the current reconciliation run can create posting instructions");
        }
        List<SettlementPostingStore.PostingWork> work = new ArrayList<>();
        for (SettlementPostingStore.SettlementPostingCandidate candidate
                : postings.findEligibleCandidates(command.tenantId(), command.runId())) {
            work.add(transactionTemplate.execute(status -> postings.ensureWork(
                    candidate, TEMPLATE_VERSION, instructionHash(candidate), clock.instant())));
        }
        return List.copyOf(work);
    }

    public List<PostingOutcome> postCurrent(PostCommand command) {
        requireCurrent(command.tenantId(), command.batchFamilyId());
        List<SettlementPostingStore.SettlementPostingCandidate> candidates =
                postings.findEligibleCandidates(command.tenantId(), command.runId());
        List<PostingOutcome> outcomes = new ArrayList<>();
        for (SettlementPostingStore.SettlementPostingCandidate candidate : candidates) {
            PostingOutcome outcome = transactionTemplate.execute(status -> postOne(command, candidate));
            if (outcome != null) {
                outcomes.add(outcome);
            }
        }
        return List.copyOf(outcomes);
    }

    private PostingOutcome postOne(
            PostCommand command,
            SettlementPostingStore.SettlementPostingCandidate candidate
    ) {
        SettlementPostingStore.PostingWork work = postings.ensureWork(
                candidate, TEMPLATE_VERSION, instructionHash(candidate), clock.instant());
        SettlementPostingStore.PostingWork locked = postings.lockWorkForPosting(
                command.tenantId(), command.batchFamilyId(), work.settlementPostingId()).orElseThrow(
                () -> new IllegalStateException("Settlement posting application does not exist"));
        ReconciliationSnapshotStore.CurrentRun current = requireCurrent(
                command.tenantId(), command.batchFamilyId());
        if (!locked.runId().equals(current.runId())) {
            throw new IllegalStateException("Settlement posting run was superseded before application");
        }
        if ("POSTED".equals(locked.applicationStatus())) {
            LedgerPostingEvidence evidence = ledger.findBySettlementPostingSource(
                    command.tenantId(), locked.settlementPostingId()).orElseThrow(
                    () -> new IllegalStateException("Posted application has no Ledger evidence"));
            return new PostingOutcome(locked.settlementPostingId(), locked.subjectType(), "REPLAYED",
                    evidence.transactionId());
        }
        if (locked.subjectType().equals("REVERSAL")
                && !postings.paymentSettlementIsPosted(
                        command.tenantId(), locked.paymentId(), TEMPLATE_VERSION)) {
            postings.recordFailure(command.tenantId(), locked.settlementPostingId(),
                    "REVERSAL_WITHOUT_PAYMENT_SETTLEMENT",
                    "Reversal settlement waits for the exact Payment settlement posting", clock.instant());
            return new PostingOutcome(locked.settlementPostingId(), locked.subjectType(),
                    "WAITING_FOR_PAYMENT", null);
        }
        LedgerPostingEvidence evidence = ledger.postSettlement(new SettlementPostingRequest(
                command.tenantId(), locked.settlementPostingId(),
                SettlementPostingType.valueOf(locked.subjectType()), locked.amount(), locked.currency()));
        postings.markPosted(command.tenantId(), locked.settlementPostingId(),
                evidence.transactionId(), clock.instant());
        return new PostingOutcome(locked.settlementPostingId(), locked.subjectType(),
                "POSTED", evidence.transactionId());
    }

    private ReconciliationSnapshotStore.CurrentRun requireCurrent(UUID tenantId, UUID batchFamilyId) {
        return snapshots.findCurrentRun(tenantId, batchFamilyId).orElseThrow(
                () -> new IllegalStateException("No current reconciliation run exists for the batch family"));
    }

    private String instructionHash(SettlementPostingStore.SettlementPostingCandidate candidate) {
        String value = String.join("|",
                candidate.tenantId().toString(), candidate.canonicalRecordVersionId().toString(),
                candidate.subjectType(), candidate.subjectId().toString(), TEMPLATE_VERSION,
                candidate.amount().toPlainString(), candidate.currency().getCurrencyCode());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record PrepareCommand(UUID tenantId, UUID batchFamilyId, UUID runId) {
        public PrepareCommand {
            Objects.requireNonNull(tenantId, "Tenant ID must not be null");
            Objects.requireNonNull(batchFamilyId, "Batch family ID must not be null");
            Objects.requireNonNull(runId, "Run ID must not be null");
        }
    }

    public record PostCommand(UUID tenantId, UUID batchFamilyId, UUID runId) {
        public PostCommand {
            Objects.requireNonNull(tenantId, "Tenant ID must not be null");
            Objects.requireNonNull(batchFamilyId, "Batch family ID must not be null");
            Objects.requireNonNull(runId, "Run ID must not be null");
        }
    }

    public record PostingOutcome(
            UUID settlementPostingId,
            String subjectType,
            String status,
            UUID ledgerTransactionId
    ) {
    }
}
