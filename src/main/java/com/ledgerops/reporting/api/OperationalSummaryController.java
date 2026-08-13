package com.ledgerops.reporting.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reports/operational-summary")
class OperationalSummaryController {

    private final OperationalSummaryQuery summary;

    OperationalSummaryController(OperationalSummaryQuery summary) {
        this.summary = summary;
    }

    @GetMapping
    ResponseEntity<OperationalSummaryResponse> findSummary(
            @PathVariable UUID tenantId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(name = "merchantId", required = false) List<String> merchantIds,
            HttpServletRequest request
    ) {
        AuthorizedRequestContext authorization = ReportingTenantAuthorization.required(
                tenantId, merchantIds, request);
        Instant fromInstant = instant(from, "from");
        Instant toInstant = instant(to, "to");
        return ResponseEntity.ok(summary.findSummary(new OperationalSummaryRequest(
                tenantId, fromInstant, toInstant,
                ReportingTenantAuthorization.effectiveMerchantIds(authorization, merchantIds),
                authorization)));
    }

    @GetMapping("/records")
    ResponseEntity<OperationalSummaryRecordPage> findRecords(
            @PathVariable UUID tenantId,
            @RequestParam String metric,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(name = "merchantId", required = false) List<String> merchantIds,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "25") int limit,
            HttpServletRequest request
    ) {
        AuthorizedRequestContext authorization = ReportingTenantAuthorization.required(
                tenantId, merchantIds, request);
        return ResponseEntity.ok(summary.findRecords(new OperationalSummaryRecordsRequest(
                tenantId, metric(metric), instant(from, "from"), instant(to, "to"),
                ReportingTenantAuthorization.effectiveMerchantIds(authorization, merchantIds),
                after, limit, authorization)));
    }

    private static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant with a timezone");
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant with a timezone", exception);
        }
    }

    private static OperationalSummaryMetricCode metric(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("metric must be one of the closed operational-summary codes");
        }
        try {
            return OperationalSummaryMetricCode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("metric contains an unsupported operational-summary code", exception);
        }
    }
}
