package com.ledgerops.identity.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.PlatformAuthorityPort;
import com.ledgerops.identity.api.SupportSessionResult;
import com.ledgerops.identity.api.SupportSessionStartCommand;
import com.ledgerops.identity.domain.SupportSession;
import com.ledgerops.identity.domain.SupportSessionRepository;
import com.ledgerops.messaging.api.MessageOutbox;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportSessionServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");
    private static final UUID TENANT_ID = UUID.randomUUID();

    @Test
    void startsAnExplicitThirtyMinuteReadOnlySessionAndAuditsEachAllowedRead() {
        PlatformAuthorityPort authority = mock(PlatformAuthorityPort.class);
        SupportSessionRepository sessions = mock(SupportSessionRepository.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        when(sessions.save(any(SupportSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SupportSessionService service = new SupportSessionService(
                authority, sessions, audit, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
        AuthenticatedPrincipal actor = actor(NOW.minusSeconds(240));

        SupportSessionResult result = service.start(new SupportSessionStartCommand(
                TENANT_ID,
                true,
                "Investigate tenant incident",
                actor,
                UUID.randomUUID(),
                UUID.randomUUID()
        ));

        assertThat(result.tenantId()).isEqualTo(TENANT_ID);
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(30 * 60L));
        assertThat(result.permission()).isEqualTo("support:tenant-read");
        verify(audit).appendSupportSessionStarted(
                eq(actor.issuer()), eq(actor.subject()), eq(TENANT_ID),
                eq(result.supportSessionId()), eq("Investigate tenant incident"),
                eq(NOW), eq(result.expiresAt()), any(String.class));

        SupportSession stored = SupportSession.reconstitute(
                new com.ledgerops.identity.domain.SupportSessionId(result.supportSessionId()),
                TENANT_ID,
                actor.issuer(),
                actor.subject(),
                "Investigate tenant incident",
                actor.authenticationTime(),
                NOW,
                result.expiresAt()
        );
        when(sessions.findActive(any(), eq(NOW))).thenReturn(Optional.of(stored));

        Optional<AuthorizedRequestContext> context = service.authorize(
                result.supportSessionId(), actor, TENANT_ID,
                "support-correlation", "/api/v1/tenants/" + TENANT_ID + "/merchants");

        assertThat(context).isPresent();
        assertThat(context.orElseThrow().isSupportSession()).isTrue();
        verify(audit).appendSupportSessionRead(
                actor.issuer(), actor.subject(), TENANT_ID,
                result.supportSessionId(),
                "/api/v1/tenants/" + TENANT_ID + "/merchants",
                "support-correlation");
    }

    @Test
    void rejectsSupportStartWithoutRecentAuthentication() {
        SupportSessionService service = new SupportSessionService(
                mock(PlatformAuthorityPort.class),
                mock(SupportSessionRepository.class),
                mock(AuditAppendPort.class),
                mock(MessageOutbox.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.start(new SupportSessionStartCommand(
                TENANT_ID,
                true,
                "Stale authentication",
                actor(NOW.minusSeconds(301)),
                UUID.randomUUID(),
                UUID.randomUUID()
        ))).isInstanceOf(IllegalStateException.class);
    }

    private AuthenticatedPrincipal actor(Instant authenticationTime) {
        return new AuthenticatedPrincipal(
                "HUMAN", "https://issuer.example", "platform-admin", authenticationTime);
    }
}
