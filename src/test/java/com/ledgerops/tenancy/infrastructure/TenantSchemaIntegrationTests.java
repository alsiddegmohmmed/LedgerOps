package com.ledgerops.tenancy.infrastructure;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsTenantsTableThroughFlyway() {
        Boolean tableExists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'tenancy'
                      AND table_name = 'tenants'
                )
                """,
                Boolean.class
        );

        assertThat(tableExists).isTrue();
    }

    @Test
    void createsAppendOnlyVersionedConfigurationAndOperationalContacts() {
        assertThat(tableExists("tenant_configurations")).isTrue();
        assertThat(tableExists("operational_contacts")).isTrue();

        UUID tenantId = insertTenant();
        Timestamp now = Timestamp.from(Instant.parse("2026-08-01T12:00:00Z"));
        jdbcTemplate.update(
                """
                INSERT INTO tenancy.tenant_configurations (
                    tenant_id, version, allowed_currencies, default_locale, timezone,
                    display_settings, created_at, actor_identity
                ) VALUES (?, 1, ARRAY['SAR', 'USD'], 'en-SA', 'Asia/Riyadh',
                          CAST(? AS JSONB), ?, ?)
                """,
                tenantId, "{\"density\":\"comfortable\"}", now, "platform:bootstrap-admin"
        );
        UUID contactId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO tenancy.operational_contacts (
                    tenant_id, contact_id, version, display_name, email, purpose,
                    active, created_at, actor_identity
                ) VALUES (?, ?, 1, 'Operations Lead', 'ops@example.test',
                          'OPERATIONS', TRUE, ?, ?)
                """,
                tenantId, contactId, now, "platform:bootstrap-admin"
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT allowed_currencies::text FROM tenancy.tenant_configurations WHERE tenant_id = ? AND version = 1",
                String.class,
                tenantId
        )).isEqualTo("{SAR,USD}");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active FROM tenancy.operational_contacts WHERE tenant_id = ? AND contact_id = ? AND version = 1",
                Boolean.class,
                tenantId,
                contactId
        )).isTrue();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE tenancy.tenant_configurations SET default_locale = 'ar-SA' WHERE tenant_id = ? AND version = 1",
                tenantId
        )).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM tenancy.operational_contacts WHERE tenant_id = ? AND contact_id = ?",
                tenantId,
                contactId
        )).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsEmptyCurrenciesMalformedDisplaySettingsAndUnnormalizedContacts() {
        UUID tenantId = insertTenant();
        Timestamp now = Timestamp.from(Instant.parse("2026-08-01T12:00:00Z"));

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO tenancy.tenant_configurations (
                    tenant_id, version, allowed_currencies, default_locale, timezone,
                    display_settings, created_at, actor_identity
                ) VALUES (?, 1, ARRAY[]::TEXT[], 'en-SA', 'Asia/Riyadh',
                          CAST('{}' AS JSONB), ?, 'actor')
                """,
                tenantId, now
        )).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO tenancy.tenant_configurations (
                    tenant_id, version, allowed_currencies, default_locale, timezone,
                    display_settings, created_at, actor_identity
                ) VALUES (?, 1, ARRAY['SAR'], 'en-SA', 'Asia/Riyadh',
                          CAST('[]' AS JSONB), ?, 'actor')
                """,
                tenantId, now
        )).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO tenancy.operational_contacts (
                    tenant_id, contact_id, version, display_name, email, purpose,
                    active, created_at, actor_identity
                ) VALUES (?, ?, 1, 'Operations Lead', 'Ops@Example.test',
                          'OPERATIONS', TRUE, ?, 'actor')
                """,
                tenantId, UUID.randomUUID(), now
        )).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsReusingOperationalContactIdentityAcrossTenantsAndVersions() {
        UUID originalTenantId = insertTenant();
        UUID otherTenantId = insertTenant();
        UUID contactId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-08-01T12:00:00Z"));
        jdbcTemplate.update(
                """
                INSERT INTO tenancy.operational_contacts (
                    tenant_id, contact_id, version, display_name, email, purpose,
                    active, created_at, actor_identity
                ) VALUES (?, ?, 1, 'Operations Lead', 'owner@example.test',
                          'OPERATIONS', TRUE, ?, 'platform:bootstrap-admin')
                """,
                originalTenantId, contactId, now
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO tenancy.operational_contacts (
                    tenant_id, contact_id, version, display_name, email, purpose,
                    active, created_at, actor_identity
                ) VALUES (?, ?, 2, 'Replacement Lead', 'replacement@example.test',
                          'OPERATIONS', TRUE, ?, 'platform:bootstrap-admin')
                """,
                otherTenantId, contactId, now
        )).isInstanceOf(Exception.class);

        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT tenant_id
                  FROM tenancy.operational_contacts
                 WHERE contact_id = ?
                """,
                UUID.class,
                contactId
        )).isEqualTo(originalTenantId);
    }

    private Boolean tableExists(String tableName) {
        return jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM information_schema.tables
                     WHERE table_schema = 'tenancy'
                       AND table_name = ?
                )
                """,
                Boolean.class,
                tableName
        );
    }

    private UUID insertTenant() {
        UUID tenantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-08-01T12:00:00Z"));
        jdbcTemplate.update(
                """
                INSERT INTO tenancy.tenants (
                    id, name, default_currency, default_locale, status,
                    version, created_at, updated_at
                ) VALUES (?, ?, 'SAR', 'en-SA', 'PENDING_ACTIVATION', 0, ?, ?)
                """,
                tenantId, "Configuration Tenant " + tenantId, now, now
        );
        return tenantId;
    }
}
