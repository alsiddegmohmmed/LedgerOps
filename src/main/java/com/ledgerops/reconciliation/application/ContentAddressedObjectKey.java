package com.ledgerops.reconciliation.application;

import java.util.UUID;

public final class ContentAddressedObjectKey {

    private ContentAddressedObjectKey() {
    }

    public static String forSettlement(UUID tenantId, String sha256) {
        if (tenantId == null || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Tenant and lowercase SHA-256 are required");
        }
        return "settlements/tenant/" + tenantId + "/sha256/"
                + sha256.substring(0, 2) + "/" + sha256 + ".csv";
    }
}
