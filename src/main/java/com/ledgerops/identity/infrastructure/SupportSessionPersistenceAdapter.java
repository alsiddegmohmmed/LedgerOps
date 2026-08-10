package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.SupportSession;
import com.ledgerops.identity.domain.SupportSessionId;
import com.ledgerops.identity.domain.SupportSessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
class SupportSessionPersistenceAdapter implements SupportSessionRepository {

    private final JdbcTemplate jdbc;

    SupportSessionPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SupportSession save(SupportSession session) {
        jdbc.update(
                """
                INSERT INTO identity.support_sessions (
                    id, tenant_id, actor_issuer, actor_subject, reason,
                    authentication_time, started_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                session.id().value(),
                session.tenantId(),
                session.actorIssuer(),
                session.actorSubject(),
                session.reason(),
                Timestamp.from(session.authenticationTime()),
                Timestamp.from(session.startedAt()),
                Timestamp.from(session.expiresAt())
        );
        return session;
    }

    @Override
    public Optional<SupportSession> findActive(SupportSessionId id, Instant now) {
        return jdbc.query(
                """
                SELECT id, tenant_id, actor_issuer, actor_subject, reason,
                       authentication_time, started_at, expires_at
                  FROM identity.support_sessions
                 WHERE id = ?
                   AND expires_at > ?
                """,
                result -> result.next()
                        ? Optional.of(SupportSession.reconstitute(
                        new SupportSessionId(result.getObject("id", java.util.UUID.class)),
                        result.getObject("tenant_id", java.util.UUID.class),
                        result.getString("actor_issuer"),
                        result.getString("actor_subject"),
                        result.getString("reason"),
                        result.getTimestamp("authentication_time").toInstant(),
                        result.getTimestamp("started_at").toInstant(),
                        result.getTimestamp("expires_at").toInstant()
                ))
                        : Optional.empty(),
                id.value(),
                Timestamp.from(now)
        );
    }
}
