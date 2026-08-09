package com.ledgerops.tenancy.application;

import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

final class TenantLifecycleOutboxFactory {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TenantLifecycleOutboxFactory() {
    }

    static OutboxMessageDraft created(
            UUID tenantId,
            UUID correlationId,
            UUID operationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "Tenant");
        payload.put("aggregateId", tenantId.toString());
        payload.put("event", "CREATED");
        payload.put("status", "PENDING_ACTIVATION");
        payload.put("version", 0);

        try {
            return new OutboxMessageDraft(
                    ProducerName.TENANCY,
                    "tenant-event:" + tenantId + ":0",
                    "TenantLifecycleChanged",
                    1,
                    tenantId,
                    tenantId,
                    "ledgerops.tenancy.lifecycle.v1",
                    tenantId.toString(),
                    JSON.writeValueAsString(payload),
                    correlationId,
                    operationId,
                    occurredAt
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Tenant lifecycle payload cannot be encoded", exception);
        }
    }

    static OutboxMessageDraft changed(
            UUID tenantId,
            String previousStatus,
            String status,
            long version,
            UUID correlationId,
            UUID operationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "Tenant");
        payload.put("aggregateId", tenantId.toString());
        payload.put("event", "STATUS_CHANGED");
        payload.put("previousStatus", previousStatus);
        payload.put("status", status);
        payload.put("version", version);

        try {
            return new OutboxMessageDraft(
                    ProducerName.TENANCY,
                    "tenant-event:" + tenantId + ":" + version,
                    "TenantLifecycleChanged",
                    1,
                    tenantId,
                    tenantId,
                    "ledgerops.tenancy.lifecycle.v1",
                    tenantId.toString(),
                    JSON.writeValueAsString(payload),
                    correlationId,
                    operationId,
                    occurredAt
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Tenant lifecycle payload cannot be encoded", exception);
        }
    }
}
