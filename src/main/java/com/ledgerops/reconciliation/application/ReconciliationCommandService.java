package com.ledgerops.reconciliation.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.reconciliation.api.ReconciliationCurrentRunSnapshot;
import com.ledgerops.reconciliation.api.ReconciliationRunSnapshot;
import com.ledgerops.reconciliation.domain.ReconciliationRunStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReconciliationCommandService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ReconciliationEngine engine;
    private final ReconciliationRunQuery query;
    private final ReconciliationSettlementPostingService posting;
    private final AuditAppendPort audit;
    private final Clock clock;

    public ReconciliationCommandService(
            ReconciliationEngine engine,
            ReconciliationRunQuery query,
            ReconciliationSettlementPostingService posting,
            AuditAppendPort audit,
            Clock clock
    ) {
        this.engine = Objects.requireNonNull(engine, "Reconciliation engine must not be null");
        this.query = Objects.requireNonNull(query, "Reconciliation query must not be null");
        this.posting = Objects.requireNonNull(posting, "Settlement posting service must not be null");
        this.audit = Objects.requireNonNull(audit, "Audit port must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public ReconciliationRunSnapshot execute(
            ReconciliationEngine.ExecuteCommand command,
            boolean confirmation,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        requireTenantWide(command.tenantId(), authorization, authorization.canRunReconciliation(),
                "reconciliation:run");
        if (!confirmation) {
            throw new IllegalArgumentException("Reconciliation execution requires confirmation");
        }
        ReconciliationRunExecution execution = engine.execute(command);
        ReconciliationRunSnapshot result = query.findRun(command.tenantId(), execution.runId())
                .orElseThrow(() -> new IllegalStateException("Completed reconciliation run is not readable"));
        audit.appendAction(
                actor.issuer(), actor.subject(), actor.principalType(), command.tenantId(),
                "reconciliation.run-executed", "reconciliation-run", execution.runId().toString(),
                "Reconciliation run executed", details(Map.of(
                        "batchVersionId", command.batchVersionId(),
                        "rulesVersion", command.rulesVersion(),
                        "sourceCutoff", command.sourceCutoff(),
                        "status", result.status().name())), authorization.correlationId());
        return result;
    }

    public ReconciliationCurrentRunSnapshot promote(
            ReconciliationEngine.PromoteCommand command,
            boolean confirmation,
            String reason,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        requireTenantWide(command.tenantId(), authorization,
                authorization.canPromoteReconciliation(), "reconciliation:promote");
        if (!confirmation) {
            throw new IllegalArgumentException("Reconciliation promotion requires confirmation");
        }
        ReconciliationCurrentRunSnapshot current = query.findRun(command.tenantId(), command.runId())
                .filter(run -> run.status() == ReconciliationRunStatus.COMPLETED
                        || run.status() == ReconciliationRunStatus.COMPLETED_WITH_DISCREPANCIES)
                .map(ignored -> engine.promote(command, clock.instant()))
                .map(value -> new ReconciliationCurrentRunSnapshot(
                        value.tenantId(), value.batchFamilyId(), value.runId(), value.promotedAt()))
                .orElseThrow(() -> new IllegalStateException(
                        "Only a completed reconciliation run can be promoted"));
        audit.appendAction(
                actor.issuer(), actor.subject(), actor.principalType(), command.tenantId(),
                "reconciliation.run-promoted", "reconciliation-run", command.runId().toString(),
                reason, details(Map.of("batchFamilyId", command.batchFamilyId(),
                        "promotedAt", current.promotedAt())), authorization.correlationId());
        return current;
    }

    public List<ReconciliationSettlementPostingService.PostingOutcome> preparePosting(
            ReconciliationSettlementPostingService.PrepareCommand command,
            boolean confirmation,
            String reason,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        requireTenantWide(command.tenantId(), authorization,
                authorization.canRunReconciliation(), "reconciliation:run");
        if (!confirmation) {
            throw new IllegalArgumentException("Settlement posting preparation requires confirmation");
        }
        List<SettlementPostingStore.PostingWork> work = posting.prepareCurrentRun(command);
        audit.appendAction(
                actor.issuer(), actor.subject(), actor.principalType(), command.tenantId(),
                "reconciliation.settlement-posting-prepared", "reconciliation-run",
                command.runId().toString(), reason,
                details(Map.of("batchFamilyId", command.batchFamilyId(), "instructionCount", work.size())),
                authorization.correlationId());
        return work.stream().map(workItem -> new ReconciliationSettlementPostingService.PostingOutcome(
                workItem.settlementPostingId(), workItem.subjectType(), workItem.applicationStatus(),
                workItem.ledgerTransactionId())).toList();
    }

    public List<ReconciliationSettlementPostingService.PostingOutcome> post(
            ReconciliationSettlementPostingService.PostCommand command,
            boolean confirmation,
            String reason,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        requireTenantWide(command.tenantId(), authorization,
                authorization.canRunReconciliation(), "reconciliation:run");
        if (!confirmation) {
            throw new IllegalArgumentException("Settlement posting requires confirmation");
        }
        List<ReconciliationSettlementPostingService.PostingOutcome> outcomes = posting.postCurrent(command);
        audit.appendAction(
                actor.issuer(), actor.subject(), actor.principalType(), command.tenantId(),
                "reconciliation.settlement-posting-applied", "reconciliation-run",
                command.runId().toString(), reason,
                details(Map.of("batchFamilyId", command.batchFamilyId(), "outcomeCount", outcomes.size())),
                authorization.correlationId());
        return outcomes;
    }

    private void requireTenantWide(
            UUID tenantId,
            AuthorizedRequestContext authorization,
            boolean allowed,
            String permission
    ) {
        if (authorization == null || !tenantId.equals(authorization.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!allowed || !authorization.isTenantWide()) {
            throw new AuthorizationPermissionDeniedException(permission);
        }
    }

    private String details(Map<String, Object> values) {
        try {
            return JSON.writeValueAsString(new LinkedHashMap<>(values));
        } catch (Exception exception) {
            throw new IllegalStateException("Reconciliation audit evidence could not be encoded", exception);
        }
    }
}
