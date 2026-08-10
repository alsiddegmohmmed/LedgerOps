package com.ledgerops.provider.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.PlatformAuthorityPort;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.provider.api.ProviderScenarioAssignment;
import com.ledgerops.provider.api.ProviderScenarioPort;
import com.ledgerops.provider.api.ProviderScenarioProfile;
import com.ledgerops.provider.api.ProviderScenarioScope;
import com.ledgerops.provider.api.ProviderScenarioSnapshot;
import com.ledgerops.provider.api.ProviderSettlementMode;
import com.ledgerops.provider.api.ProviderSubmissionOutcome;
import com.ledgerops.provider.api.ProviderWebhookMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProviderScenarioAdminService {

    private final ProviderScenarioPort scenarios;
    private final PlatformAuthorityPort platformAuthority;
    private final AuditAppendPort audit;
    private final MessageOutbox outbox;
    private final Clock clock;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID SYSTEM_TENANT_ID = UUID.nameUUIDFromBytes(
            "ledgerops:provider-scenario:system".getBytes(StandardCharsets.UTF_8));

    public ProviderScenarioAdminService(
            ProviderScenarioPort scenarios,
            PlatformAuthorityPort platformAuthority,
            AuditAppendPort audit,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.scenarios = Objects.requireNonNull(scenarios, "Scenario port must not be null");
        this.platformAuthority = Objects.requireNonNull(
                platformAuthority, "Platform authority must not be null");
        this.audit = Objects.requireNonNull(audit, "Audit append port must not be null");
        this.outbox = Objects.requireNonNull(outbox, "Message outbox must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Transactional
    public ProviderScenarioProfile createProfile(
            UUID requestedProfileId,
            Long expectedPreviousVersion,
            ProviderSubmissionOutcome submissionOutcome,
            ProviderWebhookMode webhookMode,
            ProviderSettlementMode settlementMode,
            long delayMillis,
            String fixtureId,
            Map<String, String> parameters,
            AuthenticatedPrincipal actor
    ) {
        platformAuthority.requirePlatformAdmin(actor);
        UUID profileId = requestedProfileId == null ? UUID.randomUUID() : requestedProfileId;
        long version = expectedPreviousVersion == null ? 1 : expectedPreviousVersion + 1;
        ProviderScenarioProfile profile = new ProviderScenarioProfile(
                profileId, version, submissionOutcome, webhookMode, settlementMode,
                delayMillis, fixtureId, parameters == null ? Map.of() : parameters,
                clock.instant());
        ProviderScenarioProfile created = scenarios.createProfile(profile, expectedPreviousVersion);
        audit.appendAction(
                actor.issuer(), actor.subject(), actor.principalType(), null,
                "provider.scenario-profile.created", "provider-scenario-profile",
                created.profileId() + ":" + created.version(),
                "Platform Admin created an immutable Provider scenario profile",
                "{\"profileId\":\"" + created.profileId() + "\",\"version\":"
                        + created.version() + "}", null);
        return created;
    }

    @Transactional
    public ProviderScenarioAssignment assign(
            ProviderScenarioScope scope,
            UUID tenantId,
            UUID paymentId,
            UUID profileId,
            long profileVersion,
            AuthenticatedPrincipal actor
    ) {
        platformAuthority.requirePlatformAdmin(actor);
        ProviderScenarioAssignment assignment = scenarios.assign(
                scope, tenantId, paymentId, profileId, profileVersion);
        appendScenarioChanged(assignment);
        audit.appendAction(
                actor.issuer(), actor.subject(), actor.principalType(), tenantId,
                "provider.scenario-assignment.created", "provider-scenario-assignment",
                assignment.assignmentId().toString(),
                "Platform Admin assigned a Provider scenario",
                "{\"scope\":\"" + assignment.scope() + "\",\"profileId\":\""
                        + assignment.profileId() + "\",\"profileVersion\":"
                        + assignment.profileVersion() + "}", null);
        return assignment;
    }

    private void appendScenarioChanged(ProviderScenarioAssignment assignment) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("providerId", "SIMULATOR");
            payload.put("assignmentId", assignment.assignmentId());
            payload.put("scope", assignment.scope().name());
            payload.put("tenantId", assignment.tenantId());
            payload.put("paymentId", assignment.paymentId());
            payload.put("profileId", assignment.profileId());
            payload.put("profileVersion", assignment.profileVersion());
            payload.put("effectiveFrom", assignment.createdAt());
            UUID eventTenant = assignment.tenantId() == null
                    ? SYSTEM_TENANT_ID : assignment.tenantId();
            UUID eventId = assignment.assignmentId();
            outbox.appendOrGet(new OutboxMessageDraft(
                    ProducerName.PROVIDER,
                    "provider-scenario:" + assignment.assignmentId() + ":1",
                    "ProviderScenarioChanged", 1, eventId, eventTenant,
                    "ledgerops.provider.lifecycle.v1", "SIMULATOR",
                    JSON.writeValueAsString(payload), eventId, eventId,
                    assignment.createdAt()));
        } catch (Exception exception) {
            throw new IllegalStateException("Provider scenario event could not be encoded", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<ProviderScenarioAssignment> assignments(AuthenticatedPrincipal actor) {
        platformAuthority.requirePlatformAdmin(actor);
        return scenarios.assignments();
    }

    @Transactional(readOnly = true)
    public ProviderScenarioProfile profile(
            UUID profileId,
            long version,
            AuthenticatedPrincipal actor
    ) {
        platformAuthority.requirePlatformAdmin(actor);
        return scenarios.findProfile(profileId, version).orElseThrow(() ->
                new IllegalArgumentException("Provider scenario profile does not exist"));
    }

    @Transactional(readOnly = true)
    public ProviderScenarioSnapshot pinned(
            UUID tenantId,
            UUID paymentId,
            String operationType,
            AuthenticatedPrincipal actor
    ) {
        platformAuthority.requirePlatformAdmin(actor);
        return scenarios.findPin(tenantId, paymentId, operationType).orElseThrow(() ->
                new IllegalArgumentException("Provider scenario has not been pinned"));
    }
}
