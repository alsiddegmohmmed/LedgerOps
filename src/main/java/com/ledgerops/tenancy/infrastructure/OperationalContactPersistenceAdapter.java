package com.ledgerops.tenancy.infrastructure;

import com.ledgerops.tenancy.domain.OperationalContact;
import com.ledgerops.tenancy.domain.OperationalContactRepository;
import com.ledgerops.tenancy.domain.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class OperationalContactPersistenceAdapter implements OperationalContactRepository {

    private final JdbcTemplate jdbc;

    OperationalContactPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC template must not be null");
    }

    @Override
    public long nextVersion(TenantId tenantId, UUID contactId) {
        Long next = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(version), 0) + 1
                  FROM tenancy.operational_contacts
                 WHERE tenant_id = ?
                   AND contact_id = ?
                """,
                Long.class,
                tenantId.value(),
                contactId
        );
        return Objects.requireNonNull(next, "Contact version query returned null");
    }

    @Override
    public void append(OperationalContact contact) {
        jdbc.update(
                """
                INSERT INTO tenancy.operational_contacts (
                    tenant_id, contact_id, version, display_name, email, purpose,
                    active, created_at, actor_identity
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                contact.tenantId().value(),
                contact.contactId(),
                contact.version(),
                contact.displayName(),
                contact.email(),
                contact.purpose(),
                contact.active(),
                Timestamp.from(contact.createdAt()),
                contact.actorIdentity()
        );
    }

    @Override
    public Optional<OperationalContact> current(TenantId tenantId, UUID contactId) {
        return jdbc.query(
                        """
                        SELECT tenant_id, contact_id, version, display_name, email,
                               purpose, active, created_at, actor_identity
                          FROM tenancy.operational_contacts
                         WHERE tenant_id = ?
                           AND contact_id = ?
                         ORDER BY version DESC
                         LIMIT 1
                        """,
                        this::map,
                        tenantId.value(),
                        contactId
                )
                .stream()
                .findFirst();
    }

    private OperationalContact map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OperationalContact(
                TenantId.from(resultSet.getObject("tenant_id", UUID.class)),
                resultSet.getObject("contact_id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getString("display_name"),
                resultSet.getString("email"),
                resultSet.getString("purpose"),
                resultSet.getBoolean("active"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getString("actor_identity")
        );
    }
}
