package com.ledgerops.reporting.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.provider.api.ProviderHealthState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OperationalSummaryContractTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void acceptsAnAbsoluteExclusivePeriodAndMerchantFilter() {
        OperationalSummaryRequest request = new OperationalSummaryRequest(
                TENANT_ID, FROM, TO, Set.of(MERCHANT_ID), authorization(ScopeMode.TENANT_WIDE));

        assertThat(request.fromInclusive()).isEqualTo(FROM);
        assertThat(request.toExclusive()).isEqualTo(TO);
        assertThat(request.merchantIds()).containsExactly(MERCHANT_ID);
    }

    @Test
    void rejectsAnEmptyOrReversedPeriod() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OperationalSummaryRequest(
                TENANT_ID, FROM, FROM, Set.of(), authorization(ScopeMode.TENANT_WIDE)));
        assertThatIllegalArgumentException().isThrownBy(() -> new OperationalSummaryRequest(
                TENANT_ID, TO, FROM, Set.of(), authorization(ScopeMode.TENANT_WIDE)));
    }

    @Test
    void rejectsAnInvalidKeysetLimit() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OperationalSummaryRecordsRequest(
                TENANT_ID, OperationalSummaryMetricCode.PAYMENT_VOLUME, FROM, TO, Set.of(), null,
                0, authorization(ScopeMode.TENANT_WIDE)));
        assertThatIllegalArgumentException().isThrownBy(() -> new OperationalSummaryRecordsRequest(
                TENANT_ID, OperationalSummaryMetricCode.PAYMENT_VOLUME, FROM, TO, Set.of(), null,
                101, authorization(ScopeMode.TENANT_WIDE)));
    }

    @Test
    void zeroDenominatorRatesAreNullRatherThanZero() {
        OperationalSummaryRate rate = new OperationalSummaryRate(
                0, 0, null, link("PAYMENT_SUCCESS"), link("PAYMENT_PROVIDER_TERMINAL"));

        assertThat(rate.rate()).isNull();
    }

    @Test
    void monetaryTotalsRemainGroupedByCurrency() {
        OperationalSummaryPaymentVolume volume = new OperationalSummaryPaymentVolume(
                2,
                java.util.List.of(
                        new OperationalSummaryAmount("sar", new BigDecimal("10.00")),
                        new OperationalSummaryAmount("USD", new BigDecimal("5.00"))),
                link("PAYMENT_VOLUME"));

        assertThat(volume.amountByCurrency()).extracting(OperationalSummaryAmount::currency)
                .containsExactly("SAR", "USD");
    }

    @Test
    void providerHealthCarriesCurrentAndWorstState() {
        OperationalSummaryProviderHealth health = new OperationalSummaryProviderHealth(
                ProviderHealthState.HEALTHY,
                ProviderHealthState.DEGRADED,
                TO.minusSeconds(10),
                link("PROVIDER_HEALTH_EVALUATION"));

        assertThat(health.currentState()).isEqualTo(ProviderHealthState.HEALTHY);
        assertThat(health.worstState()).isEqualTo(ProviderHealthState.DEGRADED);
    }

    private static OperationalSummarySourceLink link(String metric) {
        return new OperationalSummarySourceLink(
                "/api/v1/tenants/" + TENANT_ID + "/reports/operational-summary/records?metric=" + metric);
    }

    private static AuthorizedRequestContext authorization(ScopeMode scopeMode) {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                UUID.randomUUID(),
                null,
                TENANT_ID,
                scopeMode,
                scopeMode == ScopeMode.MERCHANT_SET ? Set.of(MERCHANT_ID) : Set.of(),
                Set.of(Permission.REPORT_READ),
                UUID.randomUUID().toString());
    }
}
