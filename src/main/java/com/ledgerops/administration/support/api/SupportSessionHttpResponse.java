package com.ledgerops.administration.support.api;

import com.ledgerops.identity.api.SupportSessionResult;

import java.time.Instant;
import java.util.UUID;

record SupportSessionHttpResponse(
        UUID supportSessionId,
        UUID tenantId,
        Instant startedAt,
        Instant expiresAt,
        String permission
) {

    static SupportSessionHttpResponse from(SupportSessionResult result) {
        return new SupportSessionHttpResponse(
                result.supportSessionId(), result.tenantId(), result.startedAt(),
                result.expiresAt(), result.permission());
    }
}
