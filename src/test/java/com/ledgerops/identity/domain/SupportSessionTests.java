package com.ledgerops.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportSessionTests {

    private static final Instant START = Instant.parse("2026-08-10T10:00:00Z");
    private static final Instant AUTHENTICATED = START.minus(4, ChronoUnit.MINUTES);

    @Test
    void expiresExactlyThirtyMinutesAfterStartAndIsNotActiveAtExpiry() {
        SupportSession session = SupportSession.start(
                UUID.randomUUID(),
                "https://issuer.example",
                "platform-admin",
                "Investigate tenant incident",
                AUTHENTICATED,
                START
        );

        assertThat(session.expiresAt()).isEqualTo(START.plus(30, ChronoUnit.MINUTES));
        assertThat(session.isActiveAt(START.plus(29, ChronoUnit.MINUTES))).isTrue();
        assertThat(session.isActiveAt(session.expiresAt())).isFalse();
    }

    @Test
    void acceptsAuthenticationAtTheFiveMinuteBoundaryButRejectsOlderAuthentication() {
        assertThat(SupportSession.start(
                UUID.randomUUID(),
                "https://issuer.example",
                "platform-admin",
                "Recent authentication",
                START.minus(5, ChronoUnit.MINUTES),
                START
        )).isNotNull();

        assertThatThrownBy(() -> SupportSession.start(
                UUID.randomUUID(),
                "https://issuer.example",
                "platform-admin",
                "Stale authentication",
                START.minus(5, ChronoUnit.MINUTES).minusNanos(1),
                START
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFutureAuthenticationAndInvalidDurationDuringReconstitution() {
        assertThatThrownBy(() -> SupportSession.start(
                UUID.randomUUID(),
                "https://issuer.example",
                "platform-admin",
                "Future authentication",
                START.plusSeconds(1),
                START
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> SupportSession.reconstitute(
                SupportSessionId.newId(),
                UUID.randomUUID(),
                "https://issuer.example",
                "platform-admin",
                "Invalid duration",
                AUTHENTICATED,
                START,
                START.plus(31, ChronoUnit.MINUTES)
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
