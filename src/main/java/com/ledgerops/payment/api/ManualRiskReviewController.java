package com.ledgerops.payment.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.payment.application.PaymentManualRiskDecisionService;
import com.ledgerops.risk.api.RiskReviewAssignmentRequest;
import com.ledgerops.risk.api.RiskReviewPort;
import com.ledgerops.risk.api.RiskReviewSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/risk-reviews")
class ManualRiskReviewController {
    private final RiskReviewPort reviews;
    private final PaymentManualRiskDecisionService decisions;

    ManualRiskReviewController(RiskReviewPort reviews, PaymentManualRiskDecisionService decisions) {
        this.reviews = reviews;
        this.decisions = decisions;
    }

    @GetMapping
    List<RiskReviewSnapshot> queue(@PathVariable UUID tenantId, HttpServletRequest request) {
        var context = AuthorizedRequestContextRequest.required(request);
        requireTenant(context.tenantId(), tenantId);
        require(context.canReadRiskReviews(), "risk:read");
        return context.isTenantWide()
                ? reviews.queue(tenantId)
                : reviews.queue(tenantId, context.merchantIds());
    }

    @PostMapping("/{reviewId}/assignment")
    RiskReviewSnapshot assign(@PathVariable UUID tenantId, @PathVariable UUID reviewId,
                              @Valid @RequestBody AssignmentBody body,
                              HttpServletRequest request) {
        var context = AuthorizedRequestContextRequest.required(request);
        requireTenant(context.tenantId(), tenantId);
        require(context.canAssignRiskReviews(), "risk:review-assign");
        requireReviewScope(context, tenantId, reviewId);
        UUID actorId = requireApplicationUser(context.applicationUserId());
        return reviews.assign(new RiskReviewAssignmentRequest(
                tenantId, reviewId, body.analystId(), actorId, body.priority(),
                body.reason(), correlation(context.correlationId())));
    }

    @PostMapping("/{reviewId}/decisions")
    ResponseEntity<PaymentManualRiskDecisionResult> decide(
            @PathVariable UUID tenantId,
            @PathVariable UUID reviewId,
            @Valid @RequestBody DecisionBody body,
            HttpServletRequest request
    ) {
        var context = AuthorizedRequestContextRequest.required(request);
        requireTenant(context.tenantId(), tenantId);
        require(context.canDecideRiskReviews(), "risk:review-decide");
        requireReviewScope(context, tenantId, reviewId);
        UUID actorId = requireApplicationUser(context.applicationUserId());
        PaymentManualRiskDecisionResult result = decisions.decide(
                new PaymentManualRiskDecisionRequest(
                        tenantId, reviewId, body.decision(), actorId, body.reason(),
                        correlation(context.correlationId()), reviewId));
        return ResponseEntity.status(result.paymentChanged() ? HttpStatus.OK : HttpStatus.OK).body(result);
    }

    private static void requireTenant(UUID contextTenant, UUID pathTenant) {
        if (contextTenant == null || !contextTenant.equals(pathTenant)) {
            throw new IllegalArgumentException("Tenant context does not match the requested Tenant");
        }
    }

    private static void require(boolean allowed, String permission) {
        if (!allowed) throw new SecurityException("Required permission is missing: " + permission);
    }

    private void requireReviewScope(com.ledgerops.identity.api.AuthorizedRequestContext context,
                                    UUID tenantId, UUID reviewId) {
        if (context.isTenantWide()) return;
        reviews.findByTenantAndId(tenantId, reviewId)
                .filter(review -> review.merchantId() != null && context.allowsMerchant(review.merchantId()))
                .orElseThrow(AuthorizationResourceNotFoundException::new);
    }

    private static UUID requireApplicationUser(UUID value) {
        if (value == null) throw new SecurityException("Human application identity is required");
        return value;
    }

    private static UUID correlation(String value) { return UUID.fromString(value); }

    record AssignmentBody(@NotNull UUID analystId, @Min(0) int priority, @NotBlank String reason) { }
    record DecisionBody(@NotNull PaymentManualRiskDecision decision, @NotBlank String reason) { }
}
