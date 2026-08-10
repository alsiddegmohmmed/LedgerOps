package com.ledgerops.casework.api;

import com.ledgerops.casework.application.CaseApplicationService;
import com.ledgerops.casework.domain.CaseResolution;
import com.ledgerops.casework.domain.CaseStatus;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.payment.api.PaymentDetailsQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/cases")
class CaseworkController {
    private final CaseApplicationService cases;
    private final PaymentDetailsQuery paymentDetails;

    CaseworkController(CaseApplicationService cases, PaymentDetailsQuery paymentDetails) {
        this.cases = cases;
        this.paymentDetails = paymentDetails;
    }

    @GetMapping
    List<CaseSnapshot> queue(@PathVariable UUID tenantId, HttpServletRequest request) {
        var context = AuthorizedRequestContextRequest.required(request);
        checkTenant(context.tenantId(), tenantId);
        check(context.canReadCases(), "case:read");
        return context.isTenantWide()
                ? cases.queue(tenantId)
                : cases.queue(tenantId, context.merchantIds());
    }

    @PostMapping("/{caseId}/assignment")
    CaseSnapshot assign(@PathVariable UUID tenantId, @PathVariable UUID caseId,
                        @Valid @RequestBody AssignmentBody body, HttpServletRequest request) {
        var context = AuthorizedRequestContextRequest.required(request);
        checkTenant(context.tenantId(), tenantId); check(context.canAssignCases(), "case:assign");
        requireCaseScope(tenantId, caseId, context);
        UUID actor = actor(context.applicationUserId());
        return cases.assign(new CaseAssignmentRequest(tenantId, caseId, body.ownerId(), actor,
                body.reason(), correlation(context.correlationId())));
    }

    @PostMapping("/{caseId}/transitions")
    CaseSnapshot transition(@PathVariable UUID tenantId, @PathVariable UUID caseId,
                            @Valid @RequestBody TransitionBody body, HttpServletRequest request) {
        var context = AuthorizedRequestContextRequest.required(request);
        checkTenant(context.tenantId(), tenantId); check(context.canUpdateCases(), "case:update");
        requireCaseScope(tenantId, caseId, context);
        UUID actor = actor(context.applicationUserId());
        return cases.transition(new CaseTransitionRequest(tenantId, caseId, body.target(), actor,
                body.reason(), correlation(context.correlationId())));
    }

    @PostMapping("/{caseId}/notes")
    CaseSnapshot addNote(@PathVariable UUID tenantId, @PathVariable UUID caseId,
                         @Valid @RequestBody NoteBody body, HttpServletRequest request) {
        var context = AuthorizedRequestContextRequest.required(request);
        checkTenant(context.tenantId(), tenantId); check(context.canUpdateCases(), "case:update");
        requireCaseScope(tenantId, caseId, context);
        UUID actor = actor(context.applicationUserId());
        return cases.addNote(new CaseNoteRequest(tenantId, caseId, actor, body.note(),
                correlation(context.correlationId())));
    }

    @PostMapping("/{caseId}/resolution")
    CaseSnapshot resolve(@PathVariable UUID tenantId, @PathVariable UUID caseId,
                         @Valid @RequestBody ResolutionBody body, HttpServletRequest request) {
        var context = AuthorizedRequestContextRequest.required(request);
        checkTenant(context.tenantId(), tenantId); check(context.canResolveCases(), "case:resolve");
        requireCaseScope(tenantId, caseId, context);
        UUID actor = actor(context.applicationUserId());
        return cases.resolve(new CaseResolutionRequest(tenantId, caseId, body.resolution(), actor,
                body.note(), correlation(context.correlationId()), caseId));
    }

    @PostMapping("/{caseId}/close")
    CaseSnapshot close(@PathVariable UUID tenantId, @PathVariable UUID caseId,
                       @Valid @RequestBody CloseBody body, HttpServletRequest request) {
        var context = AuthorizedRequestContextRequest.required(request);
        checkTenant(context.tenantId(), tenantId); check(context.canCloseCases(), "case:close");
        requireCaseScope(tenantId, caseId, context);
        UUID actor = actor(context.applicationUserId());
        return cases.close(new CaseCloseRequest(tenantId, caseId, actor, body.reason(),
                correlation(context.correlationId())));
    }

    private static void checkTenant(UUID actual, UUID expected) {
        if (actual == null || !actual.equals(expected)) throw new SecurityException("Tenant mismatch");
    }
    private static void check(boolean allowed, String permission) {
        if (!allowed) throw new SecurityException("Required permission is missing: " + permission);
    }

    private void requireCaseScope(UUID tenantId, UUID caseId,
                                  com.ledgerops.identity.api.AuthorizedRequestContext context) {
        if (context.isTenantWide()) return;
        CaseSnapshot current = cases.findByTenantAndId(tenantId, caseId)
                .orElseThrow(AuthorizationResourceNotFoundException::new);
        boolean allowed = current.relatedPaymentId() != null
                && paymentDetails.findByTenantAndPayment(tenantId, current.relatedPaymentId())
                .map(payment -> context.allowsMerchant(payment.merchantId()))
                .orElse(false);
        if (!allowed) {
            throw new AuthorizationResourceNotFoundException();
        }
    }
    private static UUID actor(UUID value) { if (value == null) throw new SecurityException("Human identity required"); return value; }
    private static UUID correlation(String value) { return UUID.fromString(value); }

    record AssignmentBody(@NotNull UUID ownerId, @NotBlank String reason) { }
    record TransitionBody(@NotNull CaseStatus target, @NotBlank String reason) { }
    record NoteBody(@NotBlank String note) { }
    record ResolutionBody(@NotNull CaseResolution resolution, @NotBlank String note) { }
    record CloseBody(@NotBlank String reason) { }
}
