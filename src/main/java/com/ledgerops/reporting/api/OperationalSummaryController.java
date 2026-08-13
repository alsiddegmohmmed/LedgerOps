package com.ledgerops.reporting.api;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.reporting.application.BoundedCsvWriter;
import com.ledgerops.reporting.application.CsvExportColumn;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reports/operational-summary")
class OperationalSummaryController {

    private static final List<CsvExportColumn> CSV_COLUMNS = List.of(
            CsvExportColumn.safe("metric"),
            CsvExportColumn.safe("sourceType"),
            CsvExportColumn.safe("sourceId"),
            CsvExportColumn.safe("merchantId"),
            CsvExportColumn.safe("occurredAt"),
            CsvExportColumn.safe("sourceDetailHref")
    );

    private final OperationalSummaryQuery summary;
    private final BoundedCsvWriter csvWriter;
    private final AuditAppendPort audit;

    OperationalSummaryController(OperationalSummaryQuery summary) {
        this(summary, new BoundedCsvWriter(), null);
    }

    OperationalSummaryController(OperationalSummaryQuery summary, AuditAppendPort audit) {
        this(summary, new BoundedCsvWriter(), audit);
    }

    @Autowired
    OperationalSummaryController(
            OperationalSummaryQuery summary,
            BoundedCsvWriter csvWriter,
            AuditAppendPort audit
    ) {
        this.summary = summary;
        this.csvWriter = csvWriter;
        this.audit = audit;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
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

    @GetMapping(value = "/records", produces = MediaType.APPLICATION_JSON_VALUE)
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

    @GetMapping(value = "/records", produces = "text/csv")
    ResponseEntity<String> exportRecords(
            @PathVariable UUID tenantId,
            @RequestParam String metric,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(name = "merchantId", required = false) List<String> merchantIds,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "25") int limit,
            HttpServletRequest request
    ) {
        AuthorizedRequestContext authorization = ReportingTenantAuthorization.requiredExport(
                tenantId, merchantIds, request);
        OperationalSummaryMetricCode metricCode = metric(metric);
        Instant fromInstant = instant(from, "from");
        Instant toInstant = instant(to, "to");
        java.util.Set<UUID> effectiveMerchantIds =
                ReportingTenantAuthorization.effectiveMerchantIds(authorization, merchantIds);
        OperationalSummaryRecordPage page = summary.findRecords(new OperationalSummaryRecordsRequest(
                tenantId, metricCode, fromInstant, toInstant, effectiveMerchantIds,
                after, limit, authorization));

        String csv = csvWriter.write(
                CSV_COLUMNS,
                page.items().stream().map(item -> List.of(
                        metricCode.name(),
                        item.sourceType(),
                        item.sourceId().toString(),
                        item.merchantId() == null ? "" : item.merchantId().toString(),
                        item.occurredAt().toString(),
                        item.sourceDetailHref() == null ? "" : item.sourceDetailHref()
                )).toList(),
                limit);
        appendExportAudit(request, authorization, tenantId, metricCode,
                fromInstant, toInstant, effectiveMerchantIds, after, limit, page.items().size());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("operational-summary-" + metricCode.name().toLowerCase(Locale.ROOT) + ".csv")
                .build());
        headers.setCacheControl(CacheControl.noStore());
        if (page.nextAfter() != null) {
            headers.set("X-Next-After", page.nextAfter());
        }
        return ResponseEntity.ok().headers(headers).body(csv);
    }

    private void appendExportAudit(
            HttpServletRequest request,
            AuthorizedRequestContext authorization,
            UUID tenantId,
            OperationalSummaryMetricCode metricCode,
            Instant from,
            Instant to,
            java.util.Set<UUID> merchantIds,
            String after,
            int limit,
            int rowCount
    ) {
        if (audit == null) {
            return;
        }
        var actor = AuthorizedRequestContextRequest.principal(request);
        audit.appendAction(
                actor.issuer(),
                actor.subject(),
                actor.principalType(),
                tenantId,
                "report.operational-summary.exported",
                "operational-summary",
                tenantId.toString(),
                "Operational summary CSV export",
                exportDetails(authorization, metricCode, from, to, merchantIds, after, limit, rowCount),
                authorization.correlationId());
    }

    private static String exportDetails(
            AuthorizedRequestContext authorization,
            OperationalSummaryMetricCode metricCode,
            Instant from,
            Instant to,
            java.util.Set<UUID> merchantIds,
            String after,
            int limit,
            int rowCount
    ) {
        String merchantIdsJson = merchantIds.stream()
                .map(UUID::toString)
                .sorted()
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(","));
        return "{\"metric\":\"" + metricCode.name()
                + "\",\"from\":\"" + from
                + "\",\"to\":\"" + to
                + "\",\"scopeMode\":\"" + authorization.scopeMode()
                + "\",\"merchantIds\":[" + merchantIdsJson
                + "],\"afterPresent\":" + (after != null && !after.isBlank())
                + ",\"limit\":" + limit
                + ",\"rowCount\":" + rowCount + "}";
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
