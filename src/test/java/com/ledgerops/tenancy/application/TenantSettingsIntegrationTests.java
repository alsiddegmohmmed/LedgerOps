package com.ledgerops.tenancy.application;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.support.PostgresTestConfiguration;
import com.ledgerops.tenancy.domain.TenantConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantSettingsIntegrationTests {

    @Autowired
    private TenantManagementService tenants;

    @Autowired
    private TenantConfigurationService configurations;

    @Autowired
    private OperationalContactService contacts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void appendsTenantConfigurationVersionsAndAuditEvidence() {
        UUID tenantId = createTenant();
        AuthorizedRequestContext context = context(tenantId, "configuration-correlation");
        AuthenticatedPrincipal actor = actor();

        TenantConfiguration first = configurations.update(new TenantConfigurationCommand(
                com.ledgerops.tenancy.api.TenantReference.from(tenantId),
                context,
                actor,
                Set.of(Currency.getInstance("SAR")),
                Locale.forLanguageTag("en-SA"),
                ZoneId.of("Asia/Riyadh"),
                "{\"dateFormat\":\"yyyy-MM-dd\"}",
                true,
                "Configure initial Tenant display settings"
        ));
        TenantConfiguration second = configurations.update(new TenantConfigurationCommand(
                com.ledgerops.tenancy.api.TenantReference.from(tenantId),
                context,
                actor,
                Set.of(Currency.getInstance("SAR"), Currency.getInstance("USD")),
                Locale.forLanguageTag("en-US"),
                ZoneId.of("UTC"),
                "{\"dateFormat\":\"MM/dd/yyyy\"}",
                true,
                "Update Tenant display settings"
        ));

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenancy.tenant_configurations WHERE tenant_id = ?",
                Integer.class,
                tenantId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT display_settings::text FROM tenancy.tenant_configurations "
                        + "WHERE tenant_id = ? AND version = 1",
                String.class,
                tenantId
        )).isEqualTo("{\"dateFormat\": \"yyyy-MM-dd\"}");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_records WHERE tenant_id = ? "
                        + "AND action_type = 'tenant.configuration.changed'",
                Integer.class,
                tenantId
        )).isEqualTo(2);
    }

    @Test
    void appendsOperationalContactVersionsAndNormalizesEmail() {
        UUID tenantId = createTenant();
        UUID contactId = UUID.randomUUID();
        AuthorizedRequestContext context = context(tenantId, "contact-correlation");
        AuthenticatedPrincipal actor = actor();

        contacts.update(new OperationalContactCommand(
                com.ledgerops.tenancy.api.TenantReference.from(tenantId),
                context,
                actor,
                contactId,
                "Finance Team",
                "FINANCE@EXAMPLE.COM",
                "settlement",
                true
        ));
        contacts.update(new OperationalContactCommand(
                com.ledgerops.tenancy.api.TenantReference.from(tenantId),
                context,
                actor,
                contactId,
                "Finance Team",
                "finance@example.com",
                "settlement",
                false
        ));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenancy.operational_contacts "
                        + "WHERE tenant_id = ? AND contact_id = ?",
                Integer.class,
                tenantId,
                contactId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT email FROM tenancy.operational_contacts "
                        + "WHERE tenant_id = ? AND contact_id = ? AND version = 1",
                String.class,
                tenantId,
                contactId
        )).isEqualTo("finance@example.com");
        assertThat(jdbc.queryForObject(
                "SELECT active FROM tenancy.operational_contacts "
                        + "WHERE tenant_id = ? AND contact_id = ? AND version = 2",
                Boolean.class,
                tenantId,
                contactId
        )).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_records WHERE tenant_id = ? "
                        + "AND action_type = 'tenant.operational-contact.changed'",
                Integer.class,
                tenantId
        )).isEqualTo(2);
    }

    @Test
    void rejectsConfigurationWithoutTenantConfigurationPermission() {
        UUID tenantId = createTenant();
        AuthorizedRequestContext context = new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                UUID.randomUUID(),
                null,
                tenantId,
                ScopeMode.TENANT_WIDE,
                Set.of(),
                Set.of(Permission.TENANT_READ),
                "unauthorized-correlation"
        );

        assertThatThrownBy(() -> configurations.update(new TenantConfigurationCommand(
                com.ledgerops.tenancy.api.TenantReference.from(tenantId),
                context,
                actor(),
                Set.of(Currency.getInstance("SAR")),
                Locale.forLanguageTag("en-SA"),
                ZoneId.of("Asia/Riyadh"),
                "{}",
                true,
                "Attempt unauthorized Tenant configuration"
        ))).isInstanceOf(AuthorizationPermissionDeniedException.class);
    }

    @Test
    void serializesConcurrentConfigurationWritesWithDistinctVersions() throws Exception {
        UUID tenantId = createTenant();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> {
                start.await();
                return configurations.update(configurationCommand(
                        tenantId, "concurrent-one", "{\"source\":\"one\"}"));
            });
            var second = executor.submit(() -> {
                start.await();
                return configurations.update(configurationCommand(
                        tenantId, "concurrent-two", "{\"source\":\"two\"}"));
            });
            start.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS).version())
                    .isIn(1L, 2L);
            assertThat(second.get(30, TimeUnit.SECONDS).version())
                    .isIn(1L, 2L);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM tenancy.tenant_configurations WHERE tenant_id = ?",
                    Integer.class,
                    tenantId
            )).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private UUID createTenant() {
        return tenants.createTenant(new CreateTenantCommand(
                "Settings Tenant " + UUID.randomUUID(),
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA")
        )).id().value();
    }

    private AuthorizedRequestContext context(UUID tenantId, String correlationId) {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                UUID.randomUUID(),
                null,
                tenantId,
                ScopeMode.TENANT_WIDE,
                Set.of(),
                Set.of(Permission.TENANT_CONFIGURE),
                correlationId
        );
    }

    private TenantConfigurationCommand configurationCommand(
            UUID tenantId,
            String correlationId,
            String displaySettings
    ) {
        return new TenantConfigurationCommand(
                com.ledgerops.tenancy.api.TenantReference.from(tenantId),
                context(tenantId, correlationId),
                actor(),
                Set.of(Currency.getInstance("SAR")),
                Locale.forLanguageTag("en-SA"),
                ZoneId.of("Asia/Riyadh"),
                displaySettings,
                true,
                "Update Tenant configuration during concurrency test"
        );
    }

    private AuthenticatedPrincipal actor() {
        return new AuthenticatedPrincipal("HUMAN", "https://issuer.example", "tenant-admin");
    }
}
