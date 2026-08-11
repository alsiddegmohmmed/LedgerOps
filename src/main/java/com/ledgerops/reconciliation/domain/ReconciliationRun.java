package com.ledgerops.reconciliation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The lifecycle aggregate for one immutable Reconciliation run.
 *
 * Run metadata and completed counts are supplied by the application layer;
 * this type only enforces the approved lifecycle transitions.
 */
public final class ReconciliationRun {

    private final UUID runId;
    private final UUID tenantId;
    private final UUID batchFamilyId;
    private final UUID batchVersionId;
    private final UUID snapshotId;
    private final int runNumber;
    private final String rulesVersion;
    private final Instant sourceCutoff;
    private final Instant createdAt;

    private ReconciliationRunStatus status;
    private Instant startedAt;
    private Instant terminalAt;
    private ReconciliationRunCounts counts;
    private String failureReason;

    private ReconciliationRun(
            UUID runId,
            UUID tenantId,
            UUID batchFamilyId,
            UUID batchVersionId,
            UUID snapshotId,
            int runNumber,
            String rulesVersion,
            Instant sourceCutoff,
            Instant createdAt
    ) {
        this.runId = Objects.requireNonNull(runId, "Run ID must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        this.batchFamilyId = Objects.requireNonNull(batchFamilyId, "Batch family ID must not be null");
        this.batchVersionId = Objects.requireNonNull(batchVersionId, "Batch version ID must not be null");
        this.snapshotId = Objects.requireNonNull(snapshotId, "Snapshot ID must not be null");
        if (runNumber <= 0) {
            throw new IllegalArgumentException("Run number must be positive");
        }
        this.runNumber = runNumber;
        this.rulesVersion = requireVisible(rulesVersion, "Rules version", 64);
        this.sourceCutoff = Objects.requireNonNull(sourceCutoff, "Source cutoff must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Created at must not be null");
        this.status = ReconciliationRunStatus.QUEUED;
    }

    public static ReconciliationRun queued(
            UUID runId,
            UUID tenantId,
            UUID batchFamilyId,
            UUID batchVersionId,
            UUID snapshotId,
            int runNumber,
            String rulesVersion,
            Instant sourceCutoff,
            Instant createdAt
    ) {
        return new ReconciliationRun(
                runId, tenantId, batchFamilyId, batchVersionId, snapshotId,
                runNumber, rulesVersion, sourceCutoff, createdAt);
    }

    public void start(Instant now) {
        requireStatus(ReconciliationRunStatus.QUEUED, "start");
        startedAt = requireTime(now, "Started at");
        status = ReconciliationRunStatus.RUNNING;
    }

    public void complete(ReconciliationRunCounts counts, Instant now) {
        requireStatus(ReconciliationRunStatus.RUNNING, "complete");
        this.counts = Objects.requireNonNull(counts, "Run counts must not be null");
        terminalAt = requireTime(now, "Completed at");
        status = counts.hasDiscrepancies()
                ? ReconciliationRunStatus.COMPLETED_WITH_DISCREPANCIES
                : ReconciliationRunStatus.COMPLETED;
    }

    public void fail(String reason, Instant now) {
        requireStatus(ReconciliationRunStatus.RUNNING, "fail");
        failureReason = requireReason(reason, "Failure reason", 2_500);
        terminalAt = requireTime(now, "Failed at");
        status = ReconciliationRunStatus.FAILED;
    }

    public void cancel(Instant now) {
        if (status != ReconciliationRunStatus.QUEUED
                && status != ReconciliationRunStatus.RUNNING) {
            throw new IllegalStateException("Run cannot transition to CANCELLED from " + status);
        }
        terminalAt = requireTime(now, "Cancelled at");
        status = ReconciliationRunStatus.CANCELLED;
    }

    public UUID runId() {
        return runId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID batchFamilyId() {
        return batchFamilyId;
    }

    public UUID batchVersionId() {
        return batchVersionId;
    }

    public UUID snapshotId() {
        return snapshotId;
    }

    public int runNumber() {
        return runNumber;
    }

    public String rulesVersion() {
        return rulesVersion;
    }

    public Instant sourceCutoff() {
        return sourceCutoff;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ReconciliationRunStatus status() {
        return status;
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> terminalAt() {
        return Optional.ofNullable(terminalAt);
    }

    public Optional<ReconciliationRunCounts> counts() {
        return Optional.ofNullable(counts);
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    private void requireStatus(ReconciliationRunStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Run cannot " + action + " from " + status + "; expected " + expected);
        }
    }

    private static Instant requireTime(Instant value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }

    private static String requireVisible(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!value.matches("[\\x21-\\x7E]{1," + maxLength + "}")) {
            throw new IllegalArgumentException(field + " must contain visible ASCII characters only");
        }
        return value;
    }

    private static String requireReason(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.chars().anyMatch(character -> character < 0x20 || character == 0x7F)) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
        return normalized;
    }
}
