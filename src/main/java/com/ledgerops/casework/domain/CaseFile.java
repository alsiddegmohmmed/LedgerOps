package com.ledgerops.casework.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CaseFile {
    private final UUID caseId;
    private final UUID tenantId;
    private final CaseSourceCategory sourceCategory;
    private final UUID sourceId;
    private final UUID relatedPaymentId;
    private final CaseSeverity severity;
    private final Instant createdAt;
    private final Instant dueAt;
    private final CaseStatus status;
    private final UUID ownerId;
    private final CaseResolution resolution;
    private final String resolutionNote;
    private final boolean correctiveActionRequired;
    private final boolean correctiveActionCompleted;
    private final List<CaseHistoryEntry> history;
    private final List<CaseNote> notes;

    private CaseFile(UUID caseId, UUID tenantId, CaseSourceCategory sourceCategory,
                     UUID sourceId, UUID relatedPaymentId, CaseSeverity severity, Instant createdAt,
                     Instant dueAt,
                     CaseStatus status, UUID ownerId, CaseResolution resolution,
                     String resolutionNote, boolean correctiveActionRequired,
                     boolean correctiveActionCompleted, List<CaseHistoryEntry> history,
                     List<CaseNote> notes) {
        this.caseId = Objects.requireNonNull(caseId, "Case ID must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        this.sourceCategory = Objects.requireNonNull(sourceCategory, "Case source category must not be null");
        this.sourceId = Objects.requireNonNull(sourceId, "Case source ID must not be null");
        this.relatedPaymentId = relatedPaymentId;
        this.severity = Objects.requireNonNull(severity, "Case severity must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Case creation time must not be null");
        this.dueAt = Objects.requireNonNull(dueAt, "Case due time must not be null");
        if (dueAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Case due time must not precede creation");
        }
        this.status = Objects.requireNonNull(status, "Case status must not be null");
        this.ownerId = ownerId;
        this.resolution = resolution;
        this.resolutionNote = resolutionNote;
        this.correctiveActionRequired = correctiveActionRequired;
        this.correctiveActionCompleted = correctiveActionCompleted;
        this.history = List.copyOf(Objects.requireNonNull(history, "Case history must not be null"));
        this.notes = List.copyOf(Objects.requireNonNull(notes, "Case notes must not be null"));
        if (status == CaseStatus.RESOLVED || status == CaseStatus.CLOSED) {
            requireResolution();
        }
    }

    public static CaseFile open(UUID caseId, UUID tenantId, CaseSourceCategory sourceCategory,
                                UUID sourceId, CaseSeverity severity, Instant dueAt) {
        return open(caseId, tenantId, sourceCategory, sourceId, null, severity, dueAt);
    }

    public static CaseFile open(UUID caseId, UUID tenantId, CaseSourceCategory sourceCategory,
                                UUID sourceId, UUID relatedPaymentId, CaseSeverity severity, Instant dueAt) {
        return open(caseId, tenantId, sourceCategory, sourceId, relatedPaymentId, severity, dueAt, dueAt);
    }

    public static CaseFile open(UUID caseId, UUID tenantId, CaseSourceCategory sourceCategory,
                                UUID sourceId, UUID relatedPaymentId, CaseSeverity severity,
                                Instant dueAt, Instant createdAt) {
        return new CaseFile(caseId, tenantId, sourceCategory, sourceId, relatedPaymentId, severity,
                createdAt, dueAt,
                CaseStatus.OPEN, null, null, null, false, false, List.of(), List.of());
    }

    public static CaseFile restore(UUID caseId, UUID tenantId, CaseSourceCategory sourceCategory,
                                   UUID sourceId, CaseSeverity severity, Instant dueAt, Instant createdAt,
                                   UUID relatedPaymentId,
                                   CaseStatus status, UUID ownerId, CaseResolution resolution,
                                   String resolutionNote, boolean correctiveActionRequired,
                                   boolean correctiveActionCompleted, List<CaseHistoryEntry> history,
                                   List<CaseNote> notes) {
        return new CaseFile(caseId, tenantId, sourceCategory, sourceId, relatedPaymentId, severity,
                createdAt, dueAt,
                status, ownerId, resolution, resolutionNote, correctiveActionRequired,
                correctiveActionCompleted, history, notes);
    }

    public CaseFile assign(UUID actorId, UUID newOwnerId, String reason, Instant now) {
        Objects.requireNonNull(newOwnerId, "Case owner must not be null");
        return withAssignment(newOwnerId, historyEvent("ASSIGNED", actorId, reason, now));
    }

    public CaseFile addNote(UUID actorId, String text, Instant now) {
        List<CaseNote> next = new ArrayList<>(notes);
        next.add(new CaseNote(UUID.randomUUID(), actorId, text, now));
        List<CaseHistoryEntry> nextHistory = new ArrayList<>(history);
        nextHistory.add(historyEvent("NOTE_ADDED", actorId, text, now));
        return new CaseFile(caseId, tenantId, sourceCategory, sourceId, relatedPaymentId, severity,
                createdAt, dueAt,
                status, ownerId, resolution, resolutionNote, correctiveActionRequired,
                correctiveActionCompleted, nextHistory, next);
    }

    public CaseFile transition(CaseStatus target, UUID actorId, String reason, Instant now) {
        Objects.requireNonNull(target, "Target Case status must not be null");
        if (!allowed(status, target)) {
            throw new CaseStateException("Case cannot transition from " + status + " to " + target);
        }
        if (target == CaseStatus.REOPENED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Reopening a Case requires a reason");
        }
        List<CaseHistoryEntry> next = new ArrayList<>(history);
        next.add(historyEvent("STATUS_CHANGED", actorId, reason, now, target));
        return new CaseFile(caseId, tenantId, sourceCategory, sourceId, relatedPaymentId, severity,
                createdAt, dueAt,
                target, ownerId, resolution, resolutionNote, correctiveActionRequired,
                correctiveActionCompleted, next, notes);
    }

    public CaseFile resolve(CaseResolution nextResolution, String note,
                            boolean paymentEffectApplied, UUID actorId, Instant now) {
        if (status != CaseStatus.INVESTIGATING) {
            throw new CaseStateException("Only an investigating Case may be resolved");
        }
        Objects.requireNonNull(nextResolution, "Case resolution must not be null");
        if (!nextResolution.allowedFor(sourceCategory)) {
            throw new CaseResolutionException("Resolution is not allowed for this Case source");
        }
        if (note == null || note.isBlank()) throw new IllegalArgumentException("Resolution note must not be blank");
        if (sourceCategory == CaseSourceCategory.RISK_REVIEW && !paymentEffectApplied) {
            throw new CaseResolutionException("Risk Case cannot resolve before Payment transition");
        }
        if (nextResolution == CaseResolution.APPROVED_CORRECTION) {
            throw new CaseResolutionException("Approved correction is unavailable before Slice 9");
        }
        List<CaseHistoryEntry> next = new ArrayList<>(history);
        next.add(historyEvent("RESOLVED", actorId, note, now, CaseStatus.RESOLVED));
        return new CaseFile(caseId, tenantId, sourceCategory, sourceId, relatedPaymentId, severity,
                createdAt, dueAt,
                CaseStatus.RESOLVED, ownerId, nextResolution, note,
                nextResolution == CaseResolution.APPROVED_CORRECTION,
                paymentEffectApplied, next, notes);
    }

    public CaseFile close(UUID actorId, String reason, Instant now) {
        if (status != CaseStatus.RESOLVED) throw new CaseStateException("Only a resolved Case may close");
        requireResolution();
        if (resolutionNote == null || resolutionNote.isBlank()) throw new CaseResolutionException("Case closure requires a note");
        if (correctiveActionRequired && !correctiveActionCompleted) {
            throw new CaseResolutionException("Required corrective action is incomplete");
        }
        List<CaseHistoryEntry> next = new ArrayList<>(history);
        next.add(historyEvent("CLOSED", actorId, reason, now, CaseStatus.CLOSED));
        return new CaseFile(caseId, tenantId, sourceCategory, sourceId, relatedPaymentId, severity,
                createdAt, dueAt,
                CaseStatus.CLOSED, ownerId, resolution, resolutionNote,
                correctiveActionRequired, correctiveActionCompleted, next, notes);
    }

    public boolean overdueAt(Instant now) {
        return !Objects.requireNonNull(now, "Current time must not be null").isBefore(dueAt)
                && status != CaseStatus.CLOSED;
    }

    public UUID caseId() { return caseId; }
    public UUID tenantId() { return tenantId; }
    public CaseSourceCategory sourceCategory() { return sourceCategory; }
    public UUID sourceId() { return sourceId; }
    public UUID relatedPaymentId() { return relatedPaymentId; }
    public CaseSeverity severity() { return severity; }
    public Instant createdAt() { return createdAt; }
    public Instant dueAt() { return dueAt; }
    public CaseStatus status() { return status; }
    public UUID ownerId() { return ownerId; }
    public CaseResolution resolution() { return resolution; }
    public String resolutionNote() { return resolutionNote; }
    public boolean correctiveActionRequired() { return correctiveActionRequired; }
    public boolean correctiveActionCompleted() { return correctiveActionCompleted; }
    public List<CaseHistoryEntry> history() { return history; }
    public List<CaseNote> notes() { return notes; }

    private CaseFile withAssignment(UUID newOwnerId, CaseHistoryEntry event) {
        List<CaseHistoryEntry> next = new ArrayList<>(history);
        next.add(event);
        return new CaseFile(caseId, tenantId, sourceCategory, sourceId, relatedPaymentId, severity,
                createdAt, dueAt,
                status, newOwnerId, resolution, resolutionNote, correctiveActionRequired,
                correctiveActionCompleted, next, notes);
    }

    private CaseHistoryEntry historyEvent(String type, UUID actorId, String reason, Instant now) {
        return historyEvent(type, actorId, reason, now, status);
    }

    private CaseHistoryEntry historyEvent(String type, UUID actorId, String reason, Instant now, CaseStatus target) {
        return new CaseHistoryEntry(history.size() + 1, type, status, target, actorId,
                reason == null || reason.isBlank() ? type : reason, now);
    }

    private void requireResolution() {
        if (resolution == null || resolutionNote == null || resolutionNote.isBlank()) {
            throw new CaseResolutionException("Case requires a resolution and explanatory note");
        }
    }

    private static boolean allowed(CaseStatus from, CaseStatus to) {
        return switch (from) {
            case OPEN -> to == CaseStatus.INVESTIGATING || to == CaseStatus.AWAITING_INFORMATION;
            case AWAITING_INFORMATION -> to == CaseStatus.INVESTIGATING;
            case INVESTIGATING -> to == CaseStatus.RESOLVED;
            case RESOLVED -> to == CaseStatus.CLOSED || to == CaseStatus.INVESTIGATING;
            case CLOSED -> to == CaseStatus.REOPENED;
            case REOPENED -> to == CaseStatus.INVESTIGATING;
        };
    }
}
