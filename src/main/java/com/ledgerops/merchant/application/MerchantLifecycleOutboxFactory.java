package com.ledgerops.merchant.application;

import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

final class MerchantLifecycleOutboxFactory {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MerchantLifecycleOutboxFactory() {
    }

    static OutboxMessageDraft created(
            UUID tenantId,
            UUID merchantId,
            UUID correlationId,
            UUID operationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "Merchant");
        payload.put("aggregateId", merchantId.toString());
        payload.put("event", "CREATED");
        payload.put("status", "ACTIVE");
        payload.put("version", 0);

        try {
            return new OutboxMessageDraft(
                    ProducerName.MERCHANT,
                    "merchant-event:" + merchantId + ":0",
                    "MerchantLifecycleChanged",
                    1,
                    merchantId,
                    tenantId,
                    "ledgerops.tenancy.lifecycle.v1",
                    tenantId.toString(),
                    JSON.writeValueAsString(payload),
                    correlationId,
                    operationId,
                    occurredAt
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Merchant lifecycle payload cannot be encoded", exception);
        }
    }

    static OutboxMessageDraft changed(
            UUID tenantId,
            UUID merchantId,
            String previousStatus,
            String status,
            long version,
            UUID correlationId,
            UUID operationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "Merchant");
        payload.put("aggregateId", merchantId.toString());
        payload.put("event", "STATUS_CHANGED");
        payload.put("previousStatus", previousStatus);
        payload.put("status", status);
        payload.put("version", version);

        try {
            return new OutboxMessageDraft(
                    ProducerName.MERCHANT,
                    "merchant-event:" + merchantId + ":" + version,
                    "MerchantLifecycleChanged",
                    1,
                    merchantId,
                    tenantId,
                    "ledgerops.tenancy.lifecycle.v1",
                    tenantId.toString(),
                    JSON.writeValueAsString(payload),
                    correlationId,
                    operationId,
                    occurredAt
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Merchant lifecycle payload cannot be encoded", exception);
        }
    }
}
