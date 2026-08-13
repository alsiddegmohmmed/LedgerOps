package com.ledgerops.reporting.api;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OperationalSummaryCsvExportTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final String FROM = "2026-08-01T00:00:00Z";
    private static final String TO = "2026-08-08T00:00:00Z";

    @Test
    void requiresTheExportPermissionInAdditionToReportRead() {
        OperationalSummaryQuery query = mock(OperationalSummaryQuery.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        OperationalSummaryController controller = new OperationalSummaryController(query, audit);
        MockHttpServletRequest request = request(context(Set.of(Permission.REPORT_READ)));

        assertThatThrownBy(() -> controller.exportRecords(
                TENANT_ID, "PAYMENT_VOLUME", FROM, TO, null, null, 25, request))
                .isInstanceOf(AuthorizationPermissionDeniedException.class)
                .hasMessageContaining("report:export");

        verifyNoInteractions(query, audit);
    }

    @Test
    void exportsOnlyTheCurrentSafeProjectionPageAndAuditsIt() {
        OperationalSummaryQuery query = mock(OperationalSummaryQuery.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        when(query.findRecords(any())).thenReturn(new OperationalSummaryRecordPage(
                List.of(new OperationalSummaryRecord(
                        "payment",
                        SOURCE_ID,
                        MERCHANT_ID,
                        Instant.parse("2026-08-02T03:04:05Z"),
                        "/api/v1/tenants/" + TENANT_ID + "/payments/" + SOURCE_ID)),
                "next-cursor"));
        OperationalSummaryController controller = new OperationalSummaryController(query, audit);
        MockHttpServletRequest request = request(context(Set.of(
                Permission.REPORT_READ, Permission.REPORT_EXPORT)));
        request.setAttribute(AuthorizedRequestContextRequest.principalAttribute(),
                new AuthenticatedPrincipal("HUMAN", "https://issuer.example", "operator"));

        var response = controller.exportRecords(
                TENANT_ID,
                "PAYMENT_VOLUME",
                FROM,
                TO,
                List.of(MERCHANT_ID.toString()),
                "previous-cursor",
                25,
                request);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .startsWith("text/csv");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("operational-summary-payment_volume.csv");
        assertThat(response.getHeaders().getFirst("X-Next-After")).isEqualTo("next-cursor");
        assertThat(response.getBody()).isEqualTo(
                "metric,sourceType,sourceId,merchantId,occurredAt,sourceDetailHref\r\n"
                        + "PAYMENT_VOLUME,payment," + SOURCE_ID + "," + MERCHANT_ID
                        + ",2026-08-02T03:04:05Z,/api/v1/tenants/" + TENANT_ID
                        + "/payments/" + SOURCE_ID + "\r\n");

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(OperationalSummaryRecordsRequest.class);
        verify(query).findRecords(requestCaptor.capture());
        assertThat(requestCaptor.getValue().metric()).isEqualTo(OperationalSummaryMetricCode.PAYMENT_VOLUME);
        assertThat(requestCaptor.getValue().merchantIds()).containsExactly(MERCHANT_ID);
        assertThat(requestCaptor.getValue().after()).isEqualTo("previous-cursor");
        verify(audit).appendAction(
                eq("https://issuer.example"),
                eq("operator"),
                eq("HUMAN"),
                eq(TENANT_ID),
                eq("report.operational-summary.exported"),
                eq("operational-summary"),
                eq(TENANT_ID.toString()),
                eq("Operational summary CSV export"),
                any(String.class),
                eq(requestCaptor.getValue().authorization().correlationId()));
    }

    private static MockHttpServletRequest request(AuthorizedRequestContext context) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthorizedRequestContext.class.getName(), context);
        return request;
    }

    private static AuthorizedRequestContext context(Set<Permission> permissions) {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                UUID.randomUUID(),
                null,
                TENANT_ID,
                ScopeMode.TENANT_WIDE,
                Set.of(),
                permissions,
                UUID.randomUUID().toString());
    }
}
