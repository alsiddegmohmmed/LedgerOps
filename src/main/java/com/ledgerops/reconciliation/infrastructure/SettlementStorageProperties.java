package com.ledgerops.reconciliation.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(prefix = "ledgerops.reconciliation.storage")
public record SettlementStorageProperties(
        URI endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        boolean pathStyleAccess
) {
}
