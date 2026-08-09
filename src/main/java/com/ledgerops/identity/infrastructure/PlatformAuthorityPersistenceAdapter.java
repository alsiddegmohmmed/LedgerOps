package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.PlatformAuthorityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

@Repository
class PlatformAuthorityPersistenceAdapter implements PlatformAuthorityRepository {

    private final JdbcTemplate jdbc;

    PlatformAuthorityPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC template must not be null");
    }

    @Override
    public boolean hasPlatformAdmin(String issuer, String subject) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM identity.platform_admin_assignments
                 WHERE issuer = ?
                   AND subject = ?
                   AND role = 'PLATFORM_ADMIN'
                """,
                Integer.class,
                issuer,
                subject
        );
        return count != null && count == 1;
    }

    @Override
    public void ensurePlatformAdmin(String issuer, String subject, Instant createdAt) {
        jdbc.update(
                """
                INSERT INTO identity.platform_admin_assignments
                    (issuer, subject, role, created_at)
                VALUES (?, ?, 'PLATFORM_ADMIN', ?)
                ON CONFLICT (issuer, subject) DO NOTHING
                """,
                issuer,
                subject,
                Timestamp.from(createdAt)
        );
    }
}
