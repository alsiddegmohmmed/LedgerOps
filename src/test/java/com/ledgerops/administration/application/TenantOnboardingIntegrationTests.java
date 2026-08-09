package com.ledgerops.administration.application;

import com.ledgerops.administration.api.TenantOnboardingCommand;
import com.ledgerops.administration.api.TenantOnboardingResult;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = {
        "ledgerops.identity.platform-admin.bootstrap-enabled=true",
        "ledgerops.identity.platform-admin.issuer=https://issuer.example",
        "ledgerops.identity.platform-admin.subject=platform-admin"
})
class TenantOnboardingIntegrationTests {

    @Autowired
    private TenantOnboardingService onboarding;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsTenantMerchantInvitationMembershipAuditAndOutboxInOneTransaction() {
        UUID correlationId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        TenantOnboardingResult result = onboarding.onboard(command(
                "Onboarding Tenant " + correlationId,
                "Onboarding Merchant " + correlationId,
                "admin-" + correlationId + "@example.com",
                correlationId.toString().replace("-", "")
                        + operationId.toString().replace("-", ""),
                correlationId,
                operationId
        ));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM tenancy.tenants WHERE id = ?",
                String.class,
                result.tenantId()
        )).isEqualTo("PENDING_ACTIVATION");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM merchant.merchants WHERE id = ? AND tenant_id = ?",
                String.class,
                result.merchantId(),
                result.tenantId()
        )).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.tenant_memberships WHERE id = ? AND tenant_id = ?",
                String.class,
                result.membershipId(),
                result.tenantId()
        )).isEqualTo("INVITED");
        assertThat(jdbc.queryForObject(
                "SELECT is_initial FROM identity.tenant_memberships WHERE id = ? AND tenant_id = ?",
                Boolean.class,
                result.membershipId(),
                result.tenantId()
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.invitations WHERE id = ? AND tenant_id = ?",
                String.class,
                result.invitationId(),
                result.tenantId()
        )).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity.invitation_grants "
                        + "WHERE invitation_id = ? AND role = 'TENANT_ADMIN' "
                        + "AND scope_mode = 'TENANT_WIDE'",
                Integer.class,
                result.invitationId()
        )).isEqualTo(1);

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT action_type, target_type, target_id, correlation_id, details "
                        + "FROM audit.audit_records WHERE correlation_id = ?",
                correlationId.toString()
        );
        assertThat(audit.get("action_type")).isEqualTo("tenant.onboarded");
        assertThat(audit.get("target_type")).isEqualTo("tenant");
        assertThat(audit.get("target_id")).isEqualTo(result.tenantId().toString());
        assertThat(audit.get("details").toString())
                .contains(result.merchantId().toString())
                .contains(result.membershipId().toString())
                .contains(result.invitationId().toString());

        List<Map<String, Object>> outbox = jdbc.queryForList(
                "SELECT producer_name, message_type, aggregate_id, causation_id, payload "
                        + "FROM messaging.outbox WHERE correlation_id = ?",
                correlationId
        );
        assertThat(outbox).hasSize(3);
        assertThat(outbox).extracting(row -> row.get("producer_name"))
                .containsExactlyInAnyOrder("tenancy", "merchant", "identity");
        assertThat(outbox).allSatisfy(row -> {
            String producer = row.get("producer_name").toString();
            String expectedMessageType = switch (producer) {
                case "tenancy" -> "TenantLifecycleChanged";
                case "merchant" -> "MerchantLifecycleChanged";
                case "identity" -> "IdentityLifecycleChanged";
                default -> throw new AssertionError("Unexpected producer: " + producer);
            };
            assertThat(row.get("message_type")).isEqualTo(expectedMessageType);
            assertThat(row.get("causation_id")).isEqualTo(operationId);
        });
    }

    @Test
    void rollsBackAllEarlierWritesWhenTheInvitationBoundaryRejectsInput() {
        UUID correlationId = UUID.randomUUID();
        String tenantName = "Rollback Tenant " + correlationId;
        String merchantName = "Rollback Merchant " + correlationId;

        assertThatThrownBy(() -> onboarding.onboard(command(
                tenantName,
                merchantName,
                "admin-" + correlationId + "@example.com",
                "not-a-token-hash",
                correlationId,
                UUID.randomUUID()
        ))).isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenancy.tenants WHERE name = ?",
                Integer.class,
                tenantName
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM merchant.merchants WHERE name = ?",
                Integer.class,
                merchantName
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_records WHERE correlation_id = ?",
                Integer.class,
                correlationId.toString()
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM messaging.outbox WHERE correlation_id = ?",
                Integer.class,
                correlationId
        )).isZero();
    }

    @Test
    void rejectsOnboardingForNonPlatformActorBeforeWritingAnything() {
        UUID correlationId = UUID.randomUUID();
        String tenantName = "Unauthorized Tenant " + correlationId;

        assertThatThrownBy(() -> onboarding.onboard(new TenantOnboardingCommand(
                tenantName,
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA"),
                "Unauthorized Merchant " + correlationId,
                "admin-" + correlationId + "@example.com",
                "a".repeat(64),
                "https://issuer.example",
                "not-platform-admin",
                correlationId,
                UUID.randomUUID()
        ))).isInstanceOf(com.ledgerops.identity.api.PlatformAuthorizationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenancy.tenants WHERE name = ?",
                Integer.class,
                tenantName
        )).isZero();
    }

    private TenantOnboardingCommand command(
            String tenantName,
            String merchantName,
            String email,
            String tokenHash,
            UUID correlationId,
            UUID operationId
    ) {
        return new TenantOnboardingCommand(
                tenantName,
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA"),
                merchantName,
                email,
                tokenHash,
                "https://issuer.example",
                "platform-admin",
                correlationId,
                operationId
        );
    }
}
