package com.ledgerops.identity.domain;

import java.time.Instant;
import java.util.Optional;

public interface SupportSessionRepository {

    SupportSession save(SupportSession session);

    Optional<SupportSession> findActive(SupportSessionId id, Instant now);
}
