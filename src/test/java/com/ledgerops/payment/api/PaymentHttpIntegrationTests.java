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

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture, fixture.merchantId(), "http-create", "125.00")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("X-Trace-Id"))
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
        String request = request(fixture, fixture.merchantId(), "http-replay", "40.00");

        MvcResult creation = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
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
    void changedMerchantReturnsIdempotencyConflictProblem() throws Exception {
        Fixture fixture = activeFixture();
        Merchant otherMerchant = new Merchant(
                MerchantId.newId(),
                TenantReference.from(fixture.tenantId()),
                "HTTP Other Merchant",
                MerchantStatus.ACTIVE
        );
        merchantRepository.save(otherMerchant);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                fixture,
                                fixture.merchantId(),
                                "http-merchant-conflict",
                                "30.00"
                        )))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                fixture,
                                otherMerchant.id().value(),
                                "http-merchant-conflict",
                                "30.00"
                        )))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.type").value(
                        "urn:ledgerops:problem:payment-idempotency-conflict"
                ))
                .andExpect(jsonPath("$.code").value("PAYMENT_IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void rejectsInvalidPaymentRequest() throws Exception {
        Fixture fixture = activeFixture();

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture, fixture.merchantId(), "", "0.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(
                        "urn:ledgerops:problem:payment-request-validation"
                ))
                .andExpect(jsonPath("$.errors.amount").exists())
                .andExpect(jsonPath("$.errors.idempotencyKey").exists());

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                fixture,
                                fixture.merchantId(),
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

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                fixture,
                                fixture.merchantId(),
                                "http-suspended-tenant",
                                "15.00"
                        )))
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
            UUID merchantId,
            String idempotencyKey,
            String amount
    ) {
        return """
                {
                  "tenantId": "%s",
                  "merchantId": "%s",
                  "customerId": "%s",
                  "amount": %s,
                  "currency": "SAR",
                  "paymentMethodCategory": "card",
                  "idempotencyKey": "%s"
                }
                """.formatted(
                fixture.tenantId(),
                merchantId,
                fixture.customerId(),
                amount,
                idempotencyKey
        );
    }

    private record Fixture(
            UUID tenantId,
            UUID merchantId,
            UUID customerId
    ) {
    }
}
