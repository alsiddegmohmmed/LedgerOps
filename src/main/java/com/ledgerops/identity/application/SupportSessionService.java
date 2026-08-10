package com.ledgerops.identity.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.PlatformAuthorityPort;
import com.ledgerops.identity.api.SupportSessionPort;
import com.ledgerops.identity.api.SupportSessionResult;
import com.ledgerops.identity.api.SupportSessionStartCommand;
import com.ledgerops.identity.domain.SupportSession;
import com.ledgerops.identity.domain.SupportSessionId;
import com.ledgerops.identity.domain.SupportSessionRepository;
import com.ledgerops.messaging.api.MessageOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
class SupportSessionService implements SupportSessionPort {

    private final PlatformAuthorityPort platformAuthority;
    private final SupportSessionRepository sessions;
    private final AuditAppendPort audit;
    private final MessageOutbox outbox;
    private final Clock clock;

    SupportSessionService(
            PlatformAuthorityPort platformAuthority,
            SupportSessionRepository sessions,
            AuditAppendPort audit,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.platformAuthority = platformAuthority;
        this.sessions = sessions;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SupportSessionResult start(SupportSessionStartCommand command) {
        platformAuthority.requirePlatformAdmin(command.actor());
        if (!command.confirmation()) {
            throw new IllegalArgumentException(
                    "Support session entry requires explicit confirmation");
        }
        Instant startedAt = clock.instant();
        if (command.actor().authenticationTime() == null
                || command.actor().authenticationTime()
                .plus(SupportSession.MAX_AUTH_AGE_MINUTES, java.time.temporal.ChronoUnit.MINUTES)
                .isBefore(startedAt)) {
            throw new IllegalStateException(
                    "Platform authentication must be no older than five minutes");
        }
        SupportSession session = SupportSession.start(
                command.tenantId(),
                command.actor().issuer(),
                command.actor().subject(),
                command.reason(),
                command.actor().authenticationTime(),
                startedAt
        );
        sessions.save(session);
        audit.appendSupportSessionStarted(
                command.actor().issuer(),
                command.actor().subject(),
                command.tenantId(),
                session.id().value(),
                command.reason(),
                session.startedAt(),
                session.expiresAt(),
                command.correlationId().toString()
        );
        outbox.appendOrGet(IdentityLifecycleOutboxFactory.supportSessionStarted(
                command.tenantId(),
                session.id().value(),
                session.startedAt(),
                session.expiresAt(),
                command.correlationId(),
                command.operationId()
        ));
        return new SupportSessionResult(
                session.id().value(),
                session.tenantId(),
                session.startedAt(),
                session.expiresAt(),
                "support:tenant-read"
        );
    }

    @Override
    @Transactional
    public Optional<AuthorizedRequestContext> authorize(
            UUID supportSessionId,
            AuthenticatedPrincipal actor,
            UUID tenantId,
            String correlationId,
            String resourcePath
    ) {
        if (supportSessionId == null || actor == null || tenantId == null
                || correlationId == null || !"HUMAN".equals(actor.principalType())) {
            return Optional.empty();
        }
        return sessions.findActive(
                        new SupportSessionId(supportSessionId), clock.instant())
                .filter(session -> session.tenantId().equals(tenantId))
                .filter(session -> session.belongsTo(actor.issuer(), actor.subject()))
                .map(session -> {
                    audit.appendSupportSessionRead(
                            actor.issuer(),
                            actor.subject(),
                            tenantId,
                            session.id().value(),
                            resourcePath,
                            correlationId
                    );
                    return AuthorizedRequestContext.support(
                            tenantId, session.id().value(), correlationId);
                });
    }
}
