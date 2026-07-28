package com.ledgerops.protectedpaths;

import com.ledgerops.customer.domain.Customer;
import com.ledgerops.customer.domain.CustomerReference;
import com.ledgerops.customer.domain.CustomerRepository;
import com.ledgerops.customer.domain.CustomerStatus;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.KeycloakIdentity;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.merchant.domain.Merchant;
import com.ledgerops.merchant.domain.MerchantId;
import com.ledgerops.merchant.domain.MerchantRepository;
import com.ledgerops.merchant.domain.MerchantStatus;
import com.ledgerops.support.PostgresTestConfiguration;
import com.ledgerops.tenancy.api.TenantReference;
import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ProtectedExistingPathsIntegrationTests {

    private static final String ISSUER = "https://keycloak.example/realms/ledgerops";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private MerchantRepository merchants;
    @Autowired private CustomerRepository customers;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void authorizedTenantReadReturnsTenantAndWrongTenantIs404() throws Exception {
        Fixture fixture = fixture(TenantStatus.ACTIVE);
        AuthorizedRequestContext context = humanContext(fixture.tenantId(), Permission.TENANT_READ);

        mockMvc.perform(get("/api/v1/tenants/{tenantId}", fixture.tenantId())
                        .requestAttr(AuthorizedRequestContext.class.getName(), context))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.tenantId().toString()));

        UUID otherTenant = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/tenants/{tenantId}", otherTenant)
                        .requestAttr(AuthorizedRequestContext.class.getName(), context))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingAuthenticationReturns401AndInsufficientPermissionReturns403() throws Exception {
        Fixture fixture = fixture(TenantStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/tenants/{tenantId}", fixture.tenantId()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/tenants/{tenantId}", fixture.tenantId())
                        .requestAttr(AuthorizedRequestContext.class.getName(),
                                humanContext(fixture.tenantId(), Permission.PAYMENT_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void paymentCreateAuditsOnlyTheOriginalEffectAndReplaysOnePayment() throws Exception {
        Fixture fixture = fixture(TenantStatus.ACTIVE);
        AuthorizedRequestContext context = serviceContext(fixture.tenantId(), fixture.merchantId());
        AuthenticatedPrincipal principal = servicePrincipal();
        String request = paymentRequest(fixture, "protected-payment-1");

        mockMvc.perform(paymentPost(fixture, context, principal, request))
                .andExpect(status().isCreated());
        mockMvc.perform(paymentPost(fixture, context, principal, request))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "select count(*) from payment.payments where tenant_id = ? and idempotency_key = ?",
                Integer.class, fixture.tenantId(), "protected-payment-1"
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit.audit_records where tenant_id = ? and action_type = 'payment.created'",
                Integer.class, fixture.tenantId()
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select correlation_id from audit.audit_records where tenant_id = ? and action_type = 'payment.created'",
                String.class, fixture.tenantId()
        )).isEqualTo("test-correlation");
    }

    @Test
    void paymentOutsideMerchantScopeIs404AndFailedCreationLeavesNoAudit() throws Exception {
        Fixture fixture = fixture(TenantStatus.SUSPENDED);
        UUID otherMerchant = UUID.randomUUID();
        AuthorizedRequestContext context = serviceContext(fixture.tenantId(), fixture.merchantId());
        AuthenticatedPrincipal principal = servicePrincipal();

        mockMvc.perform(paymentPost(fixture, context, principal, paymentRequest(fixture, "protected-payment-2")))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject(
                "select count(*) from audit.audit_records where tenant_id = ? and action_type = 'payment.created'",
                Integer.class, fixture.tenantId()
        )).isZero();

        mockMvc.perform(paymentPost(fixture, serviceContext(fixture.tenantId(), otherMerchant),
                        principal, paymentRequest(fixture, "protected-payment-3")))
                .andExpect(status().isNotFound());
    }

    @Test
    void auditFailureRollsBackThePaymentInsert() throws Exception {
        Fixture fixture = fixture(TenantStatus.ACTIVE);
        String function = "audit.reject_payment_audit_for_test";
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $$ begin raise exception 'forced audit failure'; end; $$");
        jdbc.execute("create trigger reject_payment_audit_for_test before insert on audit.audit_records for each row when (NEW.action_type = 'payment.created') execute function " + function + "()");
        try {
            mockMvc.perform(paymentPost(
                            fixture,
                            serviceContext(fixture.tenantId(), fixture.merchantId()),
                            servicePrincipal(),
                            paymentRequest(fixture, "protected-payment-rollback")
                    ))
                    .andExpect(status().is5xxServerError());
        } finally {
            jdbc.execute("drop trigger reject_payment_audit_for_test on audit.audit_records");
            jdbc.execute("drop function " + function + "()");
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from payment.payments where tenant_id = ? and idempotency_key = ?",
                Integer.class, fixture.tenantId(), "protected-payment-rollback"
        )).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from audit.audit_records where tenant_id = ? and action_type = 'payment.created'",
                Integer.class, fixture.tenantId()
        )).isZero();
    }

    private MockHttpServletRequestBuilder paymentPost(
            Fixture fixture,
            AuthorizedRequestContext context,
            AuthenticatedPrincipal principal,
            String body
    ) {
        return post("/api/v1/payments")
                .header("X-Tenant-Id", fixture.tenantId())
                .requestAttr(AuthorizedRequestContext.class.getName(), context)
                .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private AuthorizedRequestContext humanContext(UUID tenantId, Permission permission) {
        return new AuthorizedRequestContext(PrincipalType.HUMAN, UUID.randomUUID(), null,
                tenantId, ScopeMode.TENANT_WIDE, Set.of(), Set.of(permission), "test-correlation");
    }

    private AuthorizedRequestContext serviceContext(UUID tenantId, UUID merchantId) {
        return new AuthorizedRequestContext(PrincipalType.SERVICE, null, UUID.randomUUID(),
                tenantId, ScopeMode.MERCHANT_SET, Set.of(merchantId),
                Set.of(Permission.PAYMENT_CREATE), "test-correlation");
    }

    private AuthenticatedPrincipal servicePrincipal() {
        return new AuthenticatedPrincipal("SERVICE", ISSUER, "service-subject");
    }

    private Fixture fixture(TenantStatus status) {
        Tenant tenant = new Tenant(TenantId.newId(), "Protected tenant " + UUID.randomUUID(),
                Currency.getInstance("SAR"), Locale.forLanguageTag("en-SA"), status);
        tenants.save(tenant);
        Merchant merchant = new Merchant(MerchantId.newId(), TenantReference.from(tenant.id().value()),
                "Protected merchant", MerchantStatus.ACTIVE);
        merchants.save(merchant);
        com.ledgerops.customer.domain.CustomerId customerId =
                com.ledgerops.customer.domain.CustomerId.newId();
        customers.save(new Customer(customerId,
                MerchantReference.from(tenant.id().value(), merchant.id().value()),
                CustomerReference.from("protected-customer-" + UUID.randomUUID()), CustomerStatus.ACTIVE));
        return new Fixture(tenant.id().value(), merchant.id().value(), customerId.value());
    }

    private String paymentRequest(Fixture fixture, String idempotencyKey) {
        return """
                {"tenantId":"%s","merchantId":"%s","customerId":"%s","amount": "10.00",
                 "currency":"SAR","paymentMethodCategory":"card","idempotencyKey":"%s"}
                """.formatted(fixture.tenantId(), fixture.merchantId(), fixture.customerId(), idempotencyKey);
    }

    private record Fixture(UUID tenantId, UUID merchantId, UUID customerId) {
    }
}
