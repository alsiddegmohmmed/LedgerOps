package com.ledgerops.identity.domain;

import java.time.Instant;

public interface PlatformAuthorityRepository {

    boolean hasPlatformAdmin(String issuer, String subject);

    void ensurePlatformAdmin(String issuer, String subject, Instant createdAt);
}
