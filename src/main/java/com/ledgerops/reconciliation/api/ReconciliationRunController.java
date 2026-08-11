package com.ledgerops.reconciliation.api;

import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.reconciliation.application.ReconciliationCommandService;
import com.ledgerops.reconciliation.application.ReconciliationEngine;
import com.ledgerops.reconciliation.application.ReconciliationRunQuery;
import com.ledgerops.reconciliation.application.ReconciliationSettlementPostingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reconciliation-runs")
class ReconciliationRunController {

    private final ReconciliationRunQuery query;
    private final ReconciliationCommandService commands;

    ReconciliationRunController(
            ReconciliationRunQuery query,
            ReconciliationCommandService commands
    ) {
        this.query = query;
        this.commands = commands;
    }

    @PostMapping
    ResponseEntity<ReconciliationRunSnapshot> execute(
            @PathVariable UUID tenantId,
            @Valid @RequestBody ReconciliationRunHttpRequest body,
            HttpServletRequest request
    ) {
        var authorization = requireTenantWideReadWrite(
                tenantId, request, true, "reconciliation:run");
        ReconciliationRunSnapshot result = commands.execute(
                new ReconciliationEngine.ExecuteCommand(
                        tenantId, body.batchVersionId(), body.rulesVersion(), body.sourceCutoff()),
                body.confirmation(), authorization,
                AuthorizedRequestContextRequest.principal(request));
        return ResponseEntity.status(201).body(result);
    }

    @GetMapping
    List<ReconciliationRunSnapshot> findRuns(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) UUID batchFamilyId,
            @RequestParam(defaultValue = "25") int limit,
            HttpServletRequest request
    ) {
        requireTenantWideReadWrite(tenantId, request, false, "reconciliation:read");
        return query.findRuns(tenantId, Optional.ofNullable(batchFamilyId), boundedLimit(limit));
    }

    @GetMapping("/current")
    ReconciliationCurrentRunSnapshot current(
            @PathVariable UUID tenantId,
            @RequestParam UUID batchFamilyId,
            HttpServletRequest request
    ) {
        requireTenantWideReadWrite(tenantId, request, false, "reconciliation:read");
        return query.findCurrent(tenantId, batchFamilyId)
                .orElseThrow(AuthorizationResourceNotFoundException::new);
    }

    @GetMapping("/{runId}")
    ReconciliationRunSnapshot findRun(
            @PathVariable UUID tenantId,
            @PathVariable UUID runId,
            HttpServletRequest request
    ) {
        requireTenantWideReadWrite(tenantId, request, false, "reconciliation:read");
        return query.findRun(tenantId, runId)
                .orElseThrow(AuthorizationResourceNotFoundException::new);
    }

    @GetMapping("/{runId}/results")
    List<ReconciliationResultSnapshot> results(
            @PathVariable UUID tenantId,
            @PathVariable UUID runId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request
    ) {
        requireTenantWideReadWrite(tenantId, request, false, "reconciliation:read");
        requireRunExists(tenantId, runId);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        return query.findResults(tenantId, runId,
                Optional.ofNullable(enumText(status, "MATCHED", "DISCREPANCY")),
                Optional.ofNullable(categoryText(category)), boundedLimit(limit), offset);
    }

    @GetMapping("/{runId}/postings")
    List<ReconciliationPostingSnapshot> postings(
            @PathVariable UUID tenantId,
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request
    ) {
        requireTenantWideReadWrite(tenantId, request, false, "reconciliation:read");
        requireRunExists(tenantId, runId);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        return query.findPostings(tenantId, runId, boundedLimit(limit), offset);
    }

    @GetMapping("/subjects/{subjectType}/{subjectId}/status-history")
    List<ReconciliationStatusHistorySnapshot> statusHistory(
            @PathVariable UUID tenantId,
            @PathVariable String subjectType,
            @PathVariable UUID subjectId,
            HttpServletRequest request
    ) {
        requireTenantWideReadWrite(tenantId, request, false, "reconciliation:read");
        String normalizedType = enumText(subjectType, "PAYMENT", "REVERSAL");
        return query.findStatusHistory(tenantId, normalizedType, subjectId);
    }

    @PostMapping("/{runId}/promote")
    ReconciliationCurrentRunSnapshot promote(
            @PathVariable UUID tenantId,
            @PathVariable UUID runId,
            @Valid @RequestBody ReconciliationPromotionHttpRequest body,
            HttpServletRequest request
    ) {
        var authorization = requireTenantWideReadWrite(
                tenantId, request, true, "reconciliation:promote");
        return commands.promote(
                new ReconciliationEngine.PromoteCommand(tenantId, body.batchFamilyId(), runId),
                body.confirmation(), body.reason(), authorization,
                AuthorizedRequestContextRequest.principal(request));
    }

    @PostMapping("/{runId}/postings/prepare")
    List<ReconciliationSettlementPostingService.PostingOutcome> preparePosting(
            @PathVariable UUID tenantId,
            @PathVariable UUID runId,
            @Valid @RequestBody ReconciliationPostingHttpRequest body,
            HttpServletRequest request
    ) {
        var authorization = requireTenantWideReadWrite(
                tenantId, request, true, "reconciliation:run");
        return commands.preparePosting(
                new ReconciliationSettlementPostingService.PrepareCommand(
                        tenantId, body.batchFamilyId(), runId),
                body.confirmation(), body.reason(), authorization,
                AuthorizedRequestContextRequest.principal(request));
    }

    @PostMapping("/{runId}/postings")
    List<ReconciliationSettlementPostingService.PostingOutcome> post(
            @PathVariable UUID tenantId,
            @PathVariable UUID runId,
            @Valid @RequestBody ReconciliationPostingHttpRequest body,
            HttpServletRequest request
    ) {
        var authorization = requireTenantWideReadWrite(
                tenantId, request, true, "reconciliation:run");
        return commands.post(
                new ReconciliationSettlementPostingService.PostCommand(
                        tenantId, body.batchFamilyId(), runId),
                body.confirmation(), body.reason(), authorization,
                AuthorizedRequestContextRequest.principal(request));
    }

    private ReconciliationRunSnapshot requireRunExists(UUID tenantId, UUID runId) {
        return query.findRun(tenantId, runId)
                .orElseThrow(AuthorizationResourceNotFoundException::new);
    }

    private com.ledgerops.identity.api.AuthorizedRequestContext requireTenantWideReadWrite(
            UUID tenantId,
            HttpServletRequest request,
            boolean write,
            String permission
    ) {
        var authorization = AuthorizedRequestContextRequest.required(request);
        if (!tenantId.equals(authorization.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        boolean allowed = switch (permission) {
            case "reconciliation:run" -> authorization.canRunReconciliation();
            case "reconciliation:promote" -> authorization.canPromoteReconciliation();
            default -> authorization.canReadReconciliation();
        };
        if (!allowed || !authorization.isTenantWide()) {
            throw new AuthorizationPermissionDeniedException(permission);
        }
        return authorization;
    }

    private static int boundedLimit(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return value;
    }

    private static String enumText(String value, String... allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(normalized)) {
                return normalized;
            }
        }
        throw new IllegalArgumentException("Unsupported reconciliation filter: " + value);
    }

    private static String categoryText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return com.ledgerops.reconciliation.domain.ReconciliationDiscrepancyCategory
                    .valueOf(normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported discrepancy category: " + value, exception);
        }
    }
}
