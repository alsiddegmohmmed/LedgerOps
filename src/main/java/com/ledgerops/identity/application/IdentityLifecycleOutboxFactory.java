package com.ledgerops.identity.application;

import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

final class IdentityLifecycleOutboxFactory {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private IdentityLifecycleOutboxFactory() {
    }

    static OutboxMessageDraft invitationCreated(
            UUID tenantId,
            UUID membershipId,
            UUID invitationId,
            UUID correlationId,
            UUID operationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "TenantMembership");
        payload.put("aggregateId", membershipId.toString());
        payload.put("event", "INVITED");
        payload.put("version", 0);
        payload.put("invitationId", invitationId.toString());

        try {
            return new OutboxMessageDraft(
                    ProducerName.IDENTITY,
                    "identity-event:TenantMembership:" + membershipId + ":0",
                    "IdentityLifecycleChanged",
                    1,
                    membershipId,
                    tenantId,
                    "ledgerops.identity.lifecycle.v1",
                    tenantId.toString(),
                    JSON.writeValueAsString(payload),
                    correlationId,
                    operationId,
                    occurredAt
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Identity lifecycle payload cannot be encoded", exception);
        }
    }

    static OutboxMessageDraft invitationAccepted(
            UUID tenantId,
            UUID membershipId,
            UUID invitationId,
            UUID applicationUserId,
            long membershipVersion,
            UUID correlationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "TenantMembership");
        payload.put("aggregateId", membershipId.toString());
        payload.put("event", "ACCEPTED");
        payload.put("version", membershipVersion);
        payload.put("applicationUserId", applicationUserId.toString());

        try {
            return new OutboxMessageDraft(
                    ProducerName.IDENTITY,
                    "identity-event:TenantMembership:" + membershipId + ":" + membershipVersion,
                    "IdentityLifecycleChanged",
                    1,
                    membershipId,
                    tenantId,
                    "ledgerops.identity.lifecycle.v1",
                    tenantId.toString(),
                    JSON.writeValueAsString(payload),
                    correlationId,
                    invitationId,
                    occurredAt
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Identity lifecycle payload cannot be encoded", exception);
        }
    }

    static OutboxMessageDraft invitationRevoked(
            UUID tenantId,
            UUID membershipId,
            UUID invitationId,
            long membershipVersion,
            UUID correlationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "TenantMembership");
        payload.put("aggregateId", membershipId.toString());
        payload.put("event", "REVOKED");
        payload.put("version", membershipVersion);
        payload.put("invitationId", invitationId.toString());

        try {
            return new OutboxMessageDraft(
                    ProducerName.IDENTITY,
                    "identity-event:TenantMembership:" + membershipId + ":" + membershipVersion,
                    "IdentityLifecycleChanged",
                    1,
                    membershipId,
                    tenantId,
                    "ledgerops.identity.lifecycle.v1",
                    tenantId.toString(),
                    JSON.writeValueAsString(payload),
                    correlationId,
                    invitationId,
                    occurredAt
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Identity lifecycle payload cannot be encoded", exception);
        }
    }

    static OutboxMessageDraft roleAssignmentsChanged(
            UUID tenantId,
            UUID membershipId,
            long membershipVersion,
            UUID correlationId,
            UUID operationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "TenantMembership");
        payload.put("aggregateId", membershipId.toString());
        payload.put("event", "ROLE_ASSIGNMENTS_CHANGED");
        payload.put("version", membershipVersion);

        try {
            return new OutboxMessageDraft(
                    ProducerName.IDENTITY,
                    "identity-event:TenantMembership:" + membershipId + ":" + membershipVersion,
                    "IdentityLifecycleChanged",
                    1,
                    membershipId,
                    tenantId,
                    "ledgerops.identity.lifecycle.v1",
                    tenantId.toString(),
                    JSON.writeValueAsString(payload),
                    correlationId,
                    operationId,
                    occurredAt
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Identity lifecycle payload cannot be encoded", exception);
        }
    }

    static OutboxMessageDraft supportSessionStarted(
            UUID tenantId,
            UUID supportSessionId,
            Instant startedAt,
            Instant expiresAt,
            UUID correlationId,
            UUID operationId
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "SupportSession");
        payload.put("aggregateId", supportSessionId.toString());
        payload.put("event", "STARTED");
        payload.put("version", 0);
        payload.put("startedAt", startedAt.toString());
        payload.put("expiresAt", expiresAt.toString());

        try {
            return new OutboxMessageDraft(
                    ProducerName.IDENTITY,
                    "identity-event:SupportSession:" + supportSessionId + ":0",
                    "SupportSessionLifecycleChanged",
                    1,
                    supportSessionId,
                    tenantId,
                    "ledgerops.identity.support-session.v1",
                    tenantId.toString(),
                    JSON.writeValueAsString(payload),
                    correlationId,
                    operationId,
                    startedAt
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Support session payload cannot be encoded", exception);
        }
    }
}
