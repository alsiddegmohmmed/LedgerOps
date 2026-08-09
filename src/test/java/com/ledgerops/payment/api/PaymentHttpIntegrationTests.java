package com.ledgerops.payment.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerops.customer.domain.Customer;
import com.ledgerops.customer.domain.CustomerReference;
import com.ledgerops.customer.domain.CustomerRepository;
import com.ledgerops.customer.domain.CustomerStatus;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.domain.KeycloakIdentity;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class PaymentHttpIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void createsPaymentAndReturnsStableInitialResult() throws Exception {
        Fixture fixture = activeFixture();

        mockMvc.perform(authorizedPayment(fixture,
                        request(fixture, "http-create", "125.00")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.tenantId").value(fixture.tenantId().toString()))
                .andExpect(jsonPath("$.merchantId").value(fixture.merchantId().toString()))
                .andExpect(jsonPath("$.customerId").value(fixture.customerId().toString()))
                .andExpect(jsonPath("$.amount").value(125.00))
                .andExpect(jsonPath("$.currency").value("SAR"))
                .andExpect(jsonPath("$.paymentMethodCategory").value("card"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void equivalentReplayReturnsOriginalPayment() throws Exception {
        Fixture fixture = activeFixture();
        String request = request(fixture, "http-replay", "40.00");

        MvcResult creation = mockMvc.perform(authorizedPayment(fixture, request))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult replay = mockMvc.perform(authorizedPayment(fixture, request))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(
                creation.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString()
        );
        assertEquals(
                creation.getResponse().getHeader("Location"),
                replay.getResponse().getHeader("Location")
        );
    }

    @Test
    void derivesTenantAndMerchantFromAuthorizedServiceContext() throws Exception {
        Fixture fixture = activeFixture();
        mockMvc.perform(authorizedPayment(fixture, request(fixture, "http-derived-authority", "30.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(fixture.tenantId().toString()))
                .andExpect(jsonPath("$.merchantId").value(fixture.merchantId().toString()));

        mockMvc.perform(authorizedPaymentWithWrongTenantHeader(
                        fixture,
                        request(fixture, "http-derived-authority-replay", "30.00"),
                        UUID.randomUUID()
                ))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(fixture.tenantId().toString()))
                .andExpect(jsonPath("$.merchantId").value(fixture.merchantId().toString()));
    }

    @Test
    void rejectsInvalidPaymentRequest() throws Exception {
        Fixture fixture = activeFixture();

        mockMvc.perform(authorizedPayment(fixture,
                        request(fixture, "", "0.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(
                        "urn:ledgerops:problem:payment-request-validation"
                ))
                .andExpect(jsonPath("$.errors.amount").exists())
                .andExpect(jsonPath("$.errors.idempotencyKey").exists());

        mockMvc.perform(authorizedPayment(fixture, request(
                                fixture,
                                "unsupported-currency",
                                "10.00"
                        ).replace("\"SAR\"", "\"ZZZ\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_REQUEST"));
    }

    @Test
    void rejectsNewPaymentForSuspendedTenant() throws Exception {
        Fixture fixture = activeFixture();
        Tenant activeTenant = tenantRepository.findById(TenantId.from(fixture.tenantId()))
                .orElseThrow();
        tenantRepository.save(activeTenant.suspend());

        mockMvc.perform(authorizedPayment(fixture, request(fixture, "http-suspended-tenant", "15.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:ledgerops:problem:payment-reference-unavailable"
                ))
                .andExpect(jsonPath("$.referenceType").value("TENANT"))
                .andExpect(jsonPath("$.reason").value("INACTIVE"));
    }

    private Fixture activeFixture() {
        Tenant tenant = new Tenant(
                TenantId.newId(),
                "HTTP Payment Tenant " + UUID.randomUUID(),
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA"),
                TenantStatus.ACTIVE
        );
        tenantRepository.save(tenant);

        Merchant merchant = new Merchant(
                MerchantId.newId(),
                TenantReference.from(tenant.id().value()),
                "HTTP Payment Merchant",
                MerchantStatus.ACTIVE
        );
        merchantRepository.save(merchant);

        com.ledgerops.customer.domain.CustomerId customerId =
                com.ledgerops.customer.domain.CustomerId.newId();
        customerRepository.save(new Customer(
                customerId,
                MerchantReference.from(tenant.id().value(), merchant.id().value()),
                CustomerReference.from("http-payment-customer-" + UUID.randomUUID()),
                CustomerStatus.ACTIVE
        ));

        return new Fixture(
                tenant.id().value(),
                merchant.id().value(),
                customerId.value()
        );
    }

    private String request(
            Fixture fixture,
            String idempotencyKey,
            String amount
    ) {
        return """
                {
                  "customerId": "%s",
                  "amount": %s,
                  "currency": "SAR",
                  "paymentMethodCategory": "card",
                  "idempotencyKey": "%s"
                }
                """.formatted(
                fixture.customerId(),
                amount,
                idempotencyKey
        );
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizedPayment(
            Fixture fixture,
            String body
    ) {
        return authorizedPaymentWithTenantHeader(fixture, body, fixture.tenantId());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizedPaymentWithWrongTenantHeader(
            Fixture fixture,
            String body,
            UUID headerTenantId
    ) {
        return authorizedPaymentWithTenantHeader(fixture, body, headerTenantId);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizedPaymentWithTenantHeader(
            Fixture fixture,
            String body,
            UUID headerTenantId
    ) {
        AuthorizedRequestContext context = new AuthorizedRequestContext(
                PrincipalType.SERVICE,
                null,
                UUID.randomUUID(),
                fixture.tenantId(),
                ScopeMode.MERCHANT_SET,
                java.util.Set.of(fixture.merchantId()),
                java.util.Set.of(Permission.PAYMENT_CREATE),
                "test-correlation"
        );
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "SERVICE", "https://keycloak.example/realms/ledgerops", "service-subject");
        return post("/api/v1/payments")
                .header("X-Tenant-Id", headerTenantId)
                .requestAttr(AuthorizedRequestContext.class.getName(), context)
                .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private record Fixture(
            UUID tenantId,
            UUID merchantId,
            UUID customerId
    ) {
    }
}
