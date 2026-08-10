package com.ledgerops.provider.web.api;

import com.ledgerops.provider.api.ProviderScenarioAssignment;
import com.ledgerops.provider.api.ProviderScenarioProfile;
import com.ledgerops.provider.api.ProviderScenarioSnapshot;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record ProviderScenarioProfileRequest(
        UUID profileId,
        Long expectedPreviousVersion,
        @NotNull String submissionOutcome,
        @NotNull String webhookMode,
        @NotNull String settlementMode,
        @Min(0) @Max(300000) long delayMillis,
        String fixtureId,
        Map<String, String> parameters
) {
}

record ProviderScenarioAssignmentRequest(
        @NotNull String scope,
        UUID tenantId,
        UUID paymentId,
        @NotNull UUID profileId,
        @Min(1) long profileVersion
) {
}

record ProviderScenarioProfileResponse(
        UUID profileId,
        long version,
        String submissionOutcome,
        String webhookMode,
        String settlementMode,
        long delayMillis,
        String fixtureId,
        Map<String, String> parameters,
        Instant createdAt
) {
    static ProviderScenarioProfileResponse from(ProviderScenarioProfile profile) {
        return new ProviderScenarioProfileResponse(
                profile.profileId(), profile.version(), profile.submissionOutcome().name(),
                profile.webhookMode().name(), profile.settlementMode().name(),
                profile.delayMillis(), profile.fixtureId(), profile.parameters(), profile.createdAt());
    }
}

record ProviderScenarioAssignmentResponse(
        UUID assignmentId,
        String scope,
        UUID tenantId,
        UUID paymentId,
        UUID profileId,
        long profileVersion,
        boolean active,
        Instant createdAt
) {
    static ProviderScenarioAssignmentResponse from(ProviderScenarioAssignment assignment) {
        return new ProviderScenarioAssignmentResponse(
                assignment.assignmentId(), assignment.scope().name(), assignment.tenantId(),
                assignment.paymentId(), assignment.profileId(), assignment.profileVersion(),
                assignment.active(), assignment.createdAt());
    }
}

record ProviderScenarioSnapshotResponse(
        UUID profileId,
        long profileVersion,
        String submissionOutcome,
        String webhookMode,
        String settlementMode,
        long delayMillis,
        String fixtureId,
        Map<String, String> parameters,
        Instant pinnedAt
) {
    static ProviderScenarioSnapshotResponse from(ProviderScenarioSnapshot snapshot) {
        return new ProviderScenarioSnapshotResponse(
                snapshot.profileId(), snapshot.profileVersion(), snapshot.submissionOutcome().name(),
                snapshot.webhookMode().name(), snapshot.settlementMode().name(),
                snapshot.delayMillis(), snapshot.fixtureId(), snapshot.parameters(), snapshot.pinnedAt());
    }
}

record ProviderScenarioAssignmentsResponse(List<ProviderScenarioAssignmentResponse> assignments) {
}
