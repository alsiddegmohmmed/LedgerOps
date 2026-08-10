package com.ledgerops.provider.web.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.provider.api.ProviderScenarioScope;
import com.ledgerops.provider.api.ProviderSettlementMode;
import com.ledgerops.provider.api.ProviderSubmissionOutcome;
import com.ledgerops.provider.api.ProviderWebhookMode;
import com.ledgerops.provider.application.ProviderScenarioAdminService;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/platform/provider/scenarios")
class ProviderScenarioController {

    private final ProviderScenarioAdminService service;

    ProviderScenarioController(ProviderScenarioAdminService service) {
        this.service = service;
    }

    @PostMapping("/profiles")
    ResponseEntity<ProviderScenarioProfileResponse> createProfile(
            @Valid @RequestBody ProviderScenarioProfileRequest body,
            HttpServletRequest request
    ) {
        var profile = service.createProfile(
                body.profileId(), body.expectedPreviousVersion(),
                ProviderSubmissionOutcome.valueOf(body.submissionOutcome()),
                ProviderWebhookMode.valueOf(body.webhookMode()),
                ProviderSettlementMode.valueOf(body.settlementMode()),
                body.delayMillis(), body.fixtureId(), body.parameters(),
                AuthorizedRequestContextRequest.principal(request));
        return ResponseEntity.ok(ProviderScenarioProfileResponse.from(profile));
    }

    @PostMapping("/assignments")
    ResponseEntity<ProviderScenarioAssignmentResponse> assign(
            @Valid @RequestBody ProviderScenarioAssignmentRequest body,
            HttpServletRequest request
    ) {
        var assignment = service.assign(
                ProviderScenarioScope.valueOf(body.scope()), body.tenantId(), body.paymentId(),
                body.profileId(), body.profileVersion(),
                AuthorizedRequestContextRequest.principal(request));
        return ResponseEntity.ok(ProviderScenarioAssignmentResponse.from(assignment));
    }

    @GetMapping("/assignments")
    ResponseEntity<ProviderScenarioAssignmentsResponse> assignments(HttpServletRequest request) {
        List<ProviderScenarioAssignmentResponse> assignments = service.assignments(
                        AuthorizedRequestContextRequest.principal(request)).stream()
                .map(ProviderScenarioAssignmentResponse::from).toList();
        return ResponseEntity.ok(new ProviderScenarioAssignmentsResponse(assignments));
    }

    @GetMapping("/profiles/{profileId}/{version}")
    ResponseEntity<ProviderScenarioProfileResponse> profile(
            @PathVariable UUID profileId,
            @PathVariable long version,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(ProviderScenarioProfileResponse.from(service.profile(
                profileId, version, AuthorizedRequestContextRequest.principal(request))));
    }

    @GetMapping("/pins")
    ResponseEntity<ProviderScenarioSnapshotResponse> pin(
            @RequestParam UUID tenantId,
            @RequestParam UUID paymentId,
            @RequestParam(defaultValue = "PAYMENT") String operationType,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(ProviderScenarioSnapshotResponse.from(service.pinned(
                tenantId, paymentId, operationType,
                AuthorizedRequestContextRequest.principal(request))));
    }
}
