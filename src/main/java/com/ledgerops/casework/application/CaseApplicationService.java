package com.ledgerops.casework.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.casework.api.CaseAssignmentRequest;
import com.ledgerops.casework.api.CaseCloseRequest;
import com.ledgerops.casework.api.CaseCreationRequest;
import com.ledgerops.casework.api.CaseNoteRequest;
import com.ledgerops.casework.api.CaseResolutionRequest;
import com.ledgerops.casework.api.CaseSnapshot;
import com.ledgerops.casework.api.CaseTransitionRequest;
import com.ledgerops.casework.api.CaseworkPort;
import com.ledgerops.casework.domain.CaseFile;
import com.ledgerops.casework.domain.CaseResolution;
import com.ledgerops.casework.domain.CaseSourceCategory;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.InboxResult;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.payment.api.PaymentCaseResolutionPort;
import com.ledgerops.payment.api.PaymentCaseResolutionRequest;
import com.ledgerops.payment.api.PaymentDetailsQuery;
import com.ledgerops.payment.api.PaymentDetailsSnapshot;
import com.ledgerops.payment.api.RiskPaymentResolution;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class CaseApplicationService implements CaseworkPort {
    private final CaseStore store;
    private final PaymentCaseResolutionPort payment;
    private final PaymentDetailsQuery paymentDetails;
    private final MessageOutbox outbox;
    private final AuditAppendPort audit;
    private final ConsumerMessageStore messages;
    private final Clock clock;
    private final CorrectionRequestStore corrections;

    public CaseApplicationService(CaseStore store, PaymentCaseResolutionPort payment,
                                  PaymentDetailsQuery paymentDetails,
                                  MessageOutbox outbox, AuditAppendPort audit,
                                  ConsumerMessageStore messages, Clock clock,
                                  CorrectionRequestStore corrections) {
        this.store = store;
        this.payment = payment;
        this.paymentDetails = paymentDetails;
        this.outbox = outbox;
        this.audit = audit;
        this.messages = messages;
        this.clock = clock;
        this.corrections = corrections;
    }

    @Transactional
    public void applyCreateCommand(IncomingMessage incoming, CaseCreationRequest request) {
        if (messages.recordProcessed(incoming) == InboxResult.DUPLICATE) {
            return;
        }
        createIfAbsent(request);
    }

    @Override
    @Transactional
    public CaseSnapshot createIfAbsent(CaseCreationRequest request) {
        CaseFile candidate = CaseFile.open(request.caseId(), request.tenantId(),
                request.sourceCategory(), request.sourceId(), request.relatedPaymentId(),
                request.severity(), request.dueAt(), clock.instant());
        CaseFile stored = store.insertIfAbsent(candidate);
        if (stored.caseId().equals(candidate.caseId()) && stored.history().isEmpty()) {
            appendEvent(stored, "CREATED", stored.createdAt(), request.caseId());
        }
        return snapshot(stored);
    }

    @Override
    public Optional<CaseSnapshot> findByTenantAndId(UUID tenantId, UUID caseId) {
        return store.findByTenantAndId(tenantId, caseId).map(this::snapshot);
    }

    @Override
    public List<CaseSnapshot> queue(UUID tenantId) {
        return store.queue(tenantId).stream().map(this::snapshot).toList();
    }

    @Override
    public List<CaseSnapshot> queue(UUID tenantId, Set<UUID> merchantIds) {
        Set<UUID> allowed = Set.copyOf(merchantIds);
        return store.queue(tenantId).stream()
                .filter(file -> file.relatedPaymentId() != null)
                .filter(file -> paymentDetails.findByTenantAndPayment(tenantId, file.relatedPaymentId())
                        .map(PaymentDetailsSnapshot::merchantId)
                        .filter(allowed::contains)
                        .isPresent())
                .map(this::snapshot)
                .toList();
    }

    @Override
    @Transactional
    public CaseSnapshot assign(CaseAssignmentRequest request) {
        CaseFile current = lock(request.tenantId(), request.caseId());
        CaseFile updated = current.assign(request.actorId(), request.ownerId(), request.reason(), clock.instant());
        store.save(updated);
        appendEvent(updated, "ASSIGNED", clock.instant(), request.correlationId());
        audit.appendAction("application-user", request.actorId().toString(), "HUMAN",
                request.tenantId(), "case.assigned", "case", request.caseId().toString(),
                request.reason(), "{\"ownerId\":\"" + request.ownerId() + "\"}",
                request.correlationId().toString());
        return snapshot(updated);
    }

    @Override
    @Transactional
    public CaseSnapshot transition(CaseTransitionRequest request) {
        if (request.target() == com.ledgerops.casework.domain.CaseStatus.REOPENED
                && !request.confirmation()) {
            throw new CaseResolutionConsistencyException(
                    "Case reopening requires explicit confirmation");
        }
        CaseFile current = lock(request.tenantId(), request.caseId());
        CaseFile updated = current.transition(request.target(), request.actorId(), request.reason(), clock.instant());
        store.save(updated);
        appendEvent(updated, "STATUS_CHANGED", clock.instant(), request.correlationId());
        audit.appendAction("application-user", request.actorId().toString(), "HUMAN",
                request.tenantId(), "case.status-changed", "case", request.caseId().toString(),
                request.reason(), "{\"status\":\"" + request.target() + "\"}",
                request.correlationId().toString());
        return snapshot(updated);
    }

    @Override
    @Transactional
    public CaseSnapshot addNote(CaseNoteRequest request) {
        CaseFile current = lock(request.tenantId(), request.caseId());
        CaseFile updated = current.addNote(request.actorId(), request.note(), clock.instant());
        store.save(updated);
        appendEvent(updated, "NOTE_ADDED", clock.instant(), request.correlationId());
        audit.appendAction("application-user", request.actorId().toString(), "HUMAN",
                request.tenantId(), "case.note-added", "case", request.caseId().toString(),
                request.note(), "{}", request.correlationId().toString());
        return snapshot(updated);
    }

    @Override
    @Transactional
    public CaseSnapshot resolve(CaseResolutionRequest request) {
        if (!request.confirmation()) {
            throw new CaseResolutionConsistencyException(
                    "Case resolution requires explicit confirmation");
        }
        CaseFile current = lock(request.tenantId(), request.caseId());
        if (current.status() == com.ledgerops.casework.domain.CaseStatus.RESOLVED
                || current.status() == com.ledgerops.casework.domain.CaseStatus.CLOSED) {
            if (current.resolution() != request.resolution()
                    || !request.note().equals(current.resolutionNote())) {
                throw new CaseResolutionConsistencyException(
                        "Case already has a different final resolution");
            }
            if (request.resolution() == CaseResolution.APPROVED_CORRECTION
                    && corrections.findCompletedForCase(
                    request.tenantId(), request.caseId(), current.sourceId()).isEmpty()) {
                throw new CaseResolutionConsistencyException(
                        "Approved correction has no completed CorrectionRequest");
            }
            if (current.sourceCategory() == CaseSourceCategory.RISK_REVIEW) {
                if (current.relatedPaymentId() == null) {
                    throw new CaseResolutionConsistencyException("Risk Case has no related Payment");
                }
                payment.applyRiskResolution(new PaymentCaseResolutionRequest(
                        request.tenantId(), current.relatedPaymentId(), current.sourceId(),
                        current.caseId(), request.resolution() == CaseResolution.RISK_APPROVE
                        ? RiskPaymentResolution.RISK_APPROVE : RiskPaymentResolution.RISK_REJECT,
                        request.actorId(), request.note(), request.correlationId(), request.causationId()));
            }
            return snapshot(current);
        }
        boolean effectApplied = false;
        boolean correctionEffectApplied = false;
        if (current.sourceCategory() == CaseSourceCategory.RISK_REVIEW) {
            if (current.relatedPaymentId() == null) {
                throw new CaseResolutionConsistencyException("Risk Case has no related Payment");
            }
            RiskPaymentResolution resolution = request.resolution() == CaseResolution.RISK_APPROVE
                    ? RiskPaymentResolution.RISK_APPROVE : RiskPaymentResolution.RISK_REJECT;
            payment.applyRiskResolution(new PaymentCaseResolutionRequest(
                    request.tenantId(), current.relatedPaymentId(), current.sourceId(),
                    current.caseId(), resolution, request.actorId(), request.note(),
                    request.correlationId(), request.causationId()));
            effectApplied = true;
        }
        if (request.resolution() == CaseResolution.APPROVED_CORRECTION) {
            correctionEffectApplied = corrections.findCompletedForCase(
                    request.tenantId(), request.caseId(), current.sourceId()).isPresent();
        }
        CaseFile updated = current.resolve(request.resolution(), request.note(), effectApplied,
                correctionEffectApplied, request.actorId(), clock.instant());
        store.save(updated);
        appendEvent(updated, "RESOLVED", clock.instant(), request.causationId());
        audit.appendAction("application-user", request.actorId().toString(), "HUMAN",
                request.tenantId(), "case.resolved", "case", request.caseId().toString(),
                request.note(), "{\"resolution\":\"" + request.resolution() + "\"}",
                request.correlationId().toString());
        return snapshot(updated);
    }

    @Override
    @Transactional
    public CaseSnapshot close(CaseCloseRequest request) {
        if (!request.confirmation()) {
            throw new CaseResolutionConsistencyException(
                    "Case closure requires explicit confirmation");
        }
        CaseFile current = lock(request.tenantId(), request.caseId());
        CaseFile updated = current.close(request.actorId(), request.reason(), clock.instant());
        store.save(updated);
        appendEvent(updated, "CLOSED", clock.instant(), request.correlationId());
        audit.appendAction("application-user", request.actorId().toString(), "HUMAN",
                request.tenantId(), "case.closed", "case", request.caseId().toString(),
                request.reason(), "{}", request.correlationId().toString());
        return snapshot(updated);
    }

    private CaseFile lock(UUID tenantId, UUID caseId) {
        return store.lockByTenantAndId(tenantId, caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
    }

    private void appendEvent(CaseFile file, String type, java.time.Instant at, UUID causationId) {
        outbox.appendOrGet(CaseLifecycleEventFactory.draft(file, type, at,
                UUID.nameUUIDFromBytes(("case-correlation:" + file.caseId() + ":"
                        + file.history().size()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                causationId));
    }

    private CaseSnapshot snapshot(CaseFile file) {
        return new CaseSnapshot(file.caseId(), file.tenantId(), file.sourceCategory(),
                file.sourceId(), file.relatedPaymentId(), file.severity(), file.createdAt(),
                file.dueAt(), file.status(), file.ownerId(), file.resolution(), file.resolutionNote(),
                file.correctiveActionRequired(), file.correctiveActionCompleted(),
                file.history(), file.notes());
    }
}
