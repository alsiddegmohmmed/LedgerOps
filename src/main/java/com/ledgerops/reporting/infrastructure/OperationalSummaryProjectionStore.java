package com.ledgerops.reporting.infrastructure;

import com.ledgerops.provider.api.ProviderHealthState;
import com.ledgerops.reporting.api.OperationalSummaryAmount;
import com.ledgerops.reporting.api.OperationalSummaryCount;
import com.ledgerops.reporting.api.OperationalSummaryMetricCode;
import com.ledgerops.reporting.api.OperationalSummaryMetrics;
import com.ledgerops.reporting.api.OperationalSummaryPaymentVolume;
import com.ledgerops.reporting.api.OperationalSummaryPeriod;
import com.ledgerops.reporting.api.OperationalSummaryProjection;
import com.ledgerops.reporting.api.OperationalSummaryProjectionRebuildPort;
import com.ledgerops.reporting.api.OperationalSummaryProjectionRebuildRequest;
import com.ledgerops.reporting.api.OperationalSummaryProviderHealth;
import com.ledgerops.reporting.api.OperationalSummaryQuery;
import com.ledgerops.reporting.api.OperationalSummaryRecord;
import com.ledgerops.reporting.api.OperationalSummaryRecordPage;
import com.ledgerops.reporting.api.OperationalSummaryRecordsRequest;
import com.ledgerops.reporting.api.OperationalSummaryRequest;
import com.ledgerops.reporting.api.OperationalSummaryRate;
import com.ledgerops.reporting.api.OperationalSummaryScope;
import com.ledgerops.reporting.api.OperationalSummaryScopeMode;
import com.ledgerops.reporting.api.OperationalSummarySourceLink;
import com.ledgerops.reporting.api.ReportingProjectionAffected;
import com.ledgerops.reporting.api.ReportingProjectionEvent;
import com.ledgerops.reporting.api.ReportingProjectionEventQuery;
import com.ledgerops.reporting.api.ReportingProjectionEventReplay;
import com.ledgerops.reporting.application.InvalidOperationalSummaryCursorException;
import com.ledgerops.reporting.application.OperationalSummaryCursorCodec;
import com.ledgerops.reporting.application.ReportingNotReadyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Repository
class OperationalSummaryProjectionStore implements OperationalSummaryQuery,
        OperationalSummaryProjectionRebuildPort, ReportingProjectionEventQuery {

    private final JdbcTemplate jdbc;

    OperationalSummaryProjectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void rebuild(OperationalSummaryProjectionRebuildRequest request) {
        long generation = nextGeneration();
        jdbc.update("""
                INSERT INTO reporting.operational_projection_generation
                    (tenant_id, generation, cursor, as_of, completed_at)
                VALUES (?, ?, ?, ?, ?)
                """, request.tenantId(), generation, request.cursor(),
                Timestamp.from(request.asOf()), Timestamp.from(request.asOf()));

        for (com.ledgerops.reporting.api.OperationalSummaryFact fact : request.facts()) {
            jdbc.update("""
                    INSERT INTO reporting.operational_summary_fact (
                        tenant_id, generation, metric_code, source_type, source_id,
                        merchant_id, occurred_at, amount, currency, value_code,
                        current_state, current_reconciliation_run
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, fact.tenantId(), generation, fact.metric().name(), fact.sourceType(),
                    fact.sourceId(), fact.merchantId(), Timestamp.from(fact.occurredAt()),
                    fact.amount(), fact.currency(), fact.valueCode(), fact.currentState(),
                    fact.currentReconciliationRun());
        }

        long eventId = appendProjectionEvent(
                request.tenantId(), generation,
                Set.of(ReportingProjectionAffected.OPERATIONAL_SUMMARY),
                null, request.asOf());
        jdbc.update("""
                UPDATE reporting.operational_projection_generation
                   SET cursor = ?
                 WHERE tenant_id = ? AND generation = ?
                """, eventId, request.tenantId(), generation);

        jdbc.update("""
                INSERT INTO reporting.operational_projection_current
                    (tenant_id, generation, cursor, as_of, completed_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    generation = EXCLUDED.generation,
                    cursor = EXCLUDED.cursor,
                    as_of = EXCLUDED.as_of,
                    completed_at = EXCLUDED.completed_at
                """, request.tenantId(), generation, eventId,
                Timestamp.from(request.asOf()), Timestamp.from(request.asOf()));
    }

    @Override
    @Transactional(readOnly = true)
    public ReportingProjectionEventReplay replayAfter(
            UUID tenantId,
            long lastEventId,
            Set<UUID> merchantIds
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        if (lastEventId < 0) {
            throw new IllegalArgumentException("Last event ID must not be negative");
        }
        Objects.requireNonNull(merchantIds, "Merchant IDs must not be null");
        if (merchantIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Merchant IDs must not contain null");
        }

        String merchantFilter = eventMerchantFilter(merchantIds);
        List<Object> scopeArgs = new ArrayList<>();
        scopeArgs.add(tenantId);
        scopeArgs.addAll(merchantIds);
        Long earliest = jdbc.queryForObject(
                "SELECT MIN(event_id) FROM reporting.projection_event WHERE tenant_id = ?"
                        + merchantFilter,
                Long.class, scopeArgs.toArray());
        if (earliest == null) {
            return lastEventId == 0
                    ? new ReportingProjectionEventReplay(List.of(), false)
                    : ReportingProjectionEventReplay.resync();
        }

        Long latest = jdbc.queryForObject(
                "SELECT MAX(event_id) FROM reporting.projection_event WHERE tenant_id = ?"
                        + merchantFilter,
                Long.class, scopeArgs.toArray());
        if (latest != null && lastEventId >= latest) {
            return new ReportingProjectionEventReplay(List.of(), false);
        }
        if (lastEventId > 0 && lastEventId < earliest - 1) {
            return ReportingProjectionEventReplay.resync();
        }

        String sql = """
                SELECT event_id, tenant_id, generation, merchant_id, affected_codes, occurred_at
                  FROM reporting.projection_event
                 WHERE tenant_id = ? AND event_id > ?
                """ + merchantFilter + " ORDER BY event_id";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(lastEventId);
        args.addAll(merchantIds);
        List<ReportingProjectionEvent> events = jdbc.query(sql, (rs, row) ->
                new ReportingProjectionEvent(
                        rs.getLong("event_id"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getLong("generation"),
                        parseAffected(rs.getString("affected_codes")),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant()), args.toArray());
        return new ReportingProjectionEventReplay(events, false);
    }

    @Override
    @Transactional(readOnly = true)
    public com.ledgerops.reporting.api.OperationalSummaryResponse findSummary(
            OperationalSummaryRequest request
    ) {
        CurrentGeneration generation = currentGeneration(request.tenantId());
        OperationalSummaryPeriod period = new OperationalSummaryPeriod(
                request.fromInclusive(), request.toExclusive());
        String merchantPredicate = merchantPredicate(request.merchantIds());

        OperationalSummaryPaymentVolume volume = paymentVolume(request, generation, merchantPredicate);
        long success = count(request, generation, merchantPredicate,
                OperationalSummaryMetricCode.PAYMENT_SUCCESS, true);
        long failure = count(request, generation, merchantPredicate,
                OperationalSummaryMetricCode.PAYMENT_FAILURE, true);
        long denominator = count(request, generation, merchantPredicate,
                OperationalSummaryMetricCode.PAYMENT_PROVIDER_TERMINAL, true);

        OperationalSummarySourceLink successSource = sourceLink(request,
                OperationalSummaryMetricCode.PAYMENT_SUCCESS);
        OperationalSummarySourceLink failureSource = sourceLink(request,
                OperationalSummaryMetricCode.PAYMENT_FAILURE);
        OperationalSummarySourceLink terminalSource = sourceLink(request,
                OperationalSummaryMetricCode.PAYMENT_PROVIDER_TERMINAL);
        OperationalSummaryRate successRate = rate(success, denominator, successSource, terminalSource);
        OperationalSummaryRate failureRate = rate(failure, denominator, failureSource, terminalSource);

        OperationalSummaryCount manualReviews = countMetric(request, generation, merchantPredicate,
                OperationalSummaryMetricCode.MANUAL_REVIEW);
        OperationalSummaryCount openDiscrepancies = countMetric(request, generation, merchantPredicate,
                OperationalSummaryMetricCode.OPEN_DISCREPANCY);
        OperationalSummaryCount unresolvedCases = countMetric(request, generation, merchantPredicate,
                OperationalSummaryMetricCode.UNRESOLVED_CASE);
        OperationalSummaryProviderHealth providerHealth = providerHealth(
                request, generation, generation.asOf());

        return new com.ledgerops.reporting.api.OperationalSummaryResponse(
                request.tenantId(), period,
                scope(request.authorization(), request.merchantIds()), generation.asOf(),
                new OperationalSummaryProjection(generation.generation(), generation.cursor()),
                new OperationalSummaryMetrics(volume, successRate, failureRate, manualReviews,
                        openDiscrepancies, unresolvedCases, providerHealth));
    }

    @Override
    @Transactional(readOnly = true)
    public OperationalSummaryRecordPage findRecords(OperationalSummaryRecordsRequest request) {
        CurrentGeneration generation = currentGeneration(request.tenantId());
        OperationalSummaryCursorCodec.Position position = OperationalSummaryCursorCodec.decode(
                request.after(), request.metric(), request.fromInclusive(), request.toExclusive(),
                request.merchantIds());
        StringBuilder sql = new StringBuilder("""
                SELECT source_type, source_id, merchant_id, occurred_at
                  FROM reporting.operational_summary_fact
                 WHERE tenant_id = ? AND generation = ? AND metric_code = ?
                   AND occurred_at >= ? AND occurred_at < ?
                """);
        List<Object> args = new ArrayList<>(List.of(
                request.tenantId(), generation.generation(), request.metric().name(),
                Timestamp.from(request.fromInclusive()), Timestamp.from(request.toExclusive())));
        if (request.metric() != OperationalSummaryMetricCode.PROVIDER_HEALTH_EVALUATION) {
            appendMerchantFilter(sql, args, request.merchantIds());
        }
        appendMetricStateFilter(sql, request.metric());
        if (position != null) {
            sql.append(" AND (occurred_at < ? OR (occurred_at = ? AND source_id < ?))");
            args.add(Timestamp.from(position.occurredAt()));
            args.add(Timestamp.from(position.occurredAt()));
            args.add(position.sourceId());
        }
        sql.append(" ORDER BY occurred_at DESC, source_id DESC LIMIT ?");
        args.add(request.limit() + 1);

        List<FactRecord> rows = jdbc.query(sql.toString(), (rs, row) -> new FactRecord(
                rs.getString("source_type"),
                rs.getObject("source_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant()), args.toArray());
        boolean hasMore = rows.size() > request.limit();
        if (hasMore) {
            rows = rows.subList(0, request.limit());
        }
        String nextAfter = hasMore
                ? OperationalSummaryCursorCodec.encode(request.metric(), request.fromInclusive(),
                request.toExclusive(), request.merchantIds(),
                rows.get(rows.size() - 1).occurredAt(), rows.get(rows.size() - 1).sourceId())
                : null;
        return new OperationalSummaryRecordPage(
                rows.stream().map(row -> new OperationalSummaryRecord(
                        row.sourceType(), row.sourceId(), row.merchantId(), row.occurredAt(), null)).toList(),
                nextAfter);
    }

    private CurrentGeneration currentGeneration(UUID tenantId) {
        CurrentGeneration current = jdbc.query("""
                SELECT generation, cursor, as_of
                  FROM reporting.operational_projection_current
                 WHERE tenant_id = ?
                """, rs -> rs.next() ? new CurrentGeneration(
                rs.getLong("generation"), rs.getLong("cursor"),
                rs.getTimestamp("as_of").toInstant()) : null, tenantId);
        if (current == null) {
            throw new ReportingNotReadyException();
        }
        return current;
    }

    private long nextGeneration() {
        Long generation = jdbc.queryForObject(
                "SELECT nextval('reporting.operational_projection_generation_seq')",
                Long.class);
        if (generation == null || generation < 1) {
            throw new IllegalStateException(
                    "Reporting projection generation sequence returned an invalid value");
        }
        return generation;
    }

    private long appendProjectionEvent(
            UUID tenantId,
            long generation,
            Set<ReportingProjectionAffected> affected,
            UUID merchantId,
            Instant occurredAt
    ) {
        Long eventId = jdbc.queryForObject(
                "SELECT nextval('reporting.projection_event_id_seq')", Long.class);
        if (eventId == null || eventId < 1) {
            throw new IllegalStateException("Reporting event sequence returned an invalid value");
        }
        String affectedCodes = affected.stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Enum::name)
                .collect(Collectors.joining(","));
        jdbc.update("""
                INSERT INTO reporting.projection_event
                    (event_id, tenant_id, generation, merchant_id, affected_codes, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, eventId, tenantId, generation, merchantId, affectedCodes,
                Timestamp.from(occurredAt));
        return eventId;
    }

    private static Set<ReportingProjectionAffected> parseAffected(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Reporting event has no affected projection codes");
        }
        try {
            return Arrays.stream(value.split(","))
                    .map(code -> ReportingProjectionAffected.valueOf(code.trim()
                            .toUpperCase(Locale.ROOT)))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Reporting event contains an unknown projection code", exception);
        }
    }

    private static String eventMerchantFilter(Set<UUID> merchantIds) {
        if (merchantIds.isEmpty()) {
            return "";
        }
        return " AND (merchant_id IS NULL OR merchant_id IN ("
                + "?, ".repeat(Math.max(0, merchantIds.size() - 1)) + "?))";
    }

    private OperationalSummaryPaymentVolume paymentVolume(
            OperationalSummaryRequest request,
            CurrentGeneration generation,
            String merchantPredicate
    ) {
        String sql = """
                SELECT currency, COUNT(DISTINCT source_id) AS payment_count,
                       COALESCE(SUM(amount), 0) AS total_amount
                  FROM reporting.operational_summary_fact
                 WHERE tenant_id = ? AND generation = ? AND metric_code = ?
                   AND occurred_at >= ? AND occurred_at < ?
                """ + merchantPredicate + " GROUP BY currency ORDER BY currency";
        List<Object> args = baseArgs(request, generation, OperationalSummaryMetricCode.PAYMENT_VOLUME);
        appendMerchantArgs(args, request.merchantIds());
        List<OperationalSummaryAmount> amounts = jdbc.query(sql, (rs, row) -> new OperationalSummaryAmount(
                rs.getString("currency"), rs.getBigDecimal("total_amount")), args.toArray());
        long count = count(request, generation, merchantPredicate,
                OperationalSummaryMetricCode.PAYMENT_VOLUME, true);
        return new OperationalSummaryPaymentVolume(count, amounts,
                sourceLink(request, OperationalSummaryMetricCode.PAYMENT_VOLUME));
    }

    private OperationalSummaryCount countMetric(
            OperationalSummaryRequest request,
            CurrentGeneration generation,
            String merchantPredicate,
            OperationalSummaryMetricCode metric
    ) {
        return new OperationalSummaryCount(
                count(request, generation, merchantPredicate, metric, true), sourceLink(request, metric));
    }

    private long count(
            OperationalSummaryRequest request,
            CurrentGeneration generation,
            String merchantPredicate,
            OperationalSummaryMetricCode metric,
            boolean periodRequired
    ) {
        String stateFilter = metric == OperationalSummaryMetricCode.OPEN_DISCREPANCY
                ? " AND current_reconciliation_run = TRUE"
                + " AND (current_state IS NULL OR current_state <> 'CLOSED')"
                : metric == OperationalSummaryMetricCode.UNRESOLVED_CASE
                ? " AND current_state IN ('OPEN', 'INVESTIGATING', 'AWAITING_INFORMATION', 'REOPENED')"
                : "";
        String sql = """
                SELECT COUNT(DISTINCT source_id)
                  FROM reporting.operational_summary_fact
                 WHERE tenant_id = ? AND generation = ? AND metric_code = ?
                """ + (periodRequired ? " AND occurred_at >= ? AND occurred_at < ?" : "")
                + merchantPredicate + stateFilter;
        List<Object> args = new ArrayList<>(List.of(
                request.tenantId(), generation.generation(), metric.name()));
        if (periodRequired) {
            args.add(Timestamp.from(request.fromInclusive()));
            args.add(Timestamp.from(request.toExclusive()));
        }
        appendMerchantArgs(args, request.merchantIds());
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private OperationalSummaryProviderHealth providerHealth(
            OperationalSummaryRequest request,
            CurrentGeneration generation,
            Instant asOf
    ) {
        String sql = """
                SELECT value_code, occurred_at, source_id
                  FROM reporting.operational_summary_fact
                 WHERE tenant_id = ? AND generation = ?
                   AND metric_code = ? AND occurred_at <= ?
                """ + " ORDER BY occurred_at DESC, source_id DESC";
        List<Object> args = new ArrayList<>(List.of(
                request.tenantId(), generation.generation(),
                OperationalSummaryMetricCode.PROVIDER_HEALTH_EVALUATION.name(), Timestamp.from(asOf)));
        List<HealthFact> facts = jdbc.query(sql, (rs, row) -> new HealthFact(
                ProviderHealthState.valueOf(rs.getString("value_code")),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getObject("source_id", UUID.class)), args.toArray());
        if (facts.isEmpty()) {
            throw new ReportingNotReadyException();
        }
        ProviderHealthState current = facts.get(0).state();
        ProviderHealthState worst = facts.stream()
                .filter(fact -> !fact.occurredAt().isBefore(request.fromInclusive())
                        && fact.occurredAt().isBefore(request.toExclusive()))
                .map(HealthFact::state)
                .max(Comparator.comparingInt(OperationalSummaryProjectionStore::healthSeverity))
                .orElse(ProviderHealthState.UNKNOWN);
        return new OperationalSummaryProviderHealth(current, worst, facts.get(0).occurredAt(),
                sourceLink(request.tenantId(), OperationalSummaryMetricCode.PROVIDER_HEALTH_EVALUATION,
                        request.fromInclusive(), request.toExclusive(), Set.of()));
    }

    private OperationalSummaryRate rate(
            long numerator,
            long denominator,
            OperationalSummarySourceLink numeratorSource,
            OperationalSummarySourceLink denominatorSource
    ) {
        BigDecimal value = denominator == 0 ? null
                : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 10, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return new OperationalSummaryRate(numerator, denominator, value, numeratorSource, denominatorSource);
    }

    private OperationalSummaryScope scope(
            com.ledgerops.identity.api.AuthorizedRequestContext authorization,
            Set<UUID> merchantIds
    ) {
        return new OperationalSummaryScope(
                authorization.isTenantWide()
                        ? OperationalSummaryScopeMode.TENANT_WIDE
                        : OperationalSummaryScopeMode.MERCHANT_SET,
                authorization.isTenantWide() ? merchantIds : authorization.merchantIds());
    }

    private OperationalSummarySourceLink sourceLink(
            OperationalSummaryRequest request,
            OperationalSummaryMetricCode metric
    ) {
        return sourceLink(request.tenantId(), metric, request.fromInclusive(), request.toExclusive(),
                request.merchantIds());
    }

    private OperationalSummarySourceLink sourceLink(
            UUID tenantId,
            OperationalSummaryMetricCode metric,
            Instant from,
            Instant to,
            Set<UUID> merchantIds
    ) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromPath("/api/v1/tenants/{tenantId}/reports/operational-summary/records")
                .queryParam("metric", metric.name())
                .queryParam("from", from)
                .queryParam("to", to);
        merchantIds.stream().sorted().forEach(id -> builder.queryParam("merchantId", id));
        return new OperationalSummarySourceLink(
                builder.buildAndExpand(Map.of("tenantId", tenantId)).toUriString());
    }

    private static String merchantPredicate(Set<UUID> merchantIds) {
        return merchantIds.isEmpty() ? "" : " AND merchant_id IN ("
                + "?, ".repeat(Math.max(0, merchantIds.size() - 1)) + "?)";
    }

    private static void appendMerchantFilter(StringBuilder sql, List<Object> args, Set<UUID> merchantIds) {
        if (!merchantIds.isEmpty()) {
            sql.append(" AND merchant_id IN (");
            appendPlaceholders(sql, merchantIds.size());
            sql.append(")");
            args.addAll(merchantIds);
        }
    }

    private static void appendMetricStateFilter(StringBuilder sql, OperationalSummaryMetricCode metric) {
        if (metric == OperationalSummaryMetricCode.OPEN_DISCREPANCY) {
            sql.append(" AND current_reconciliation_run = TRUE")
                    .append(" AND (current_state IS NULL OR current_state <> 'CLOSED')");
        } else if (metric == OperationalSummaryMetricCode.UNRESOLVED_CASE) {
            sql.append(" AND current_state IN ('OPEN', 'INVESTIGATING', 'AWAITING_INFORMATION', 'REOPENED')");
        }
    }

    private static List<Object> baseArgs(
            OperationalSummaryRequest request,
            CurrentGeneration generation,
            OperationalSummaryMetricCode metric
    ) {
        return new ArrayList<>(List.of(
                request.tenantId(), generation.generation(), metric.name(),
                Timestamp.from(request.fromInclusive()), Timestamp.from(request.toExclusive())));
    }

    private static void appendMerchantArgs(List<Object> args, Set<UUID> merchantIds) {
        args.addAll(merchantIds);
    }

    private static void appendPlaceholders(StringBuilder sql, int count) {
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
    }

    private static int healthSeverity(ProviderHealthState state) {
        return switch (state) {
            case UNKNOWN -> 0;
            case HEALTHY -> 1;
            case DEGRADED -> 2;
            case UNAVAILABLE -> 3;
        };
    }

    private record CurrentGeneration(long generation, long cursor, Instant asOf) {
    }

    private record FactRecord(String sourceType, UUID sourceId, UUID merchantId, Instant occurredAt) {
    }

    private record HealthFact(ProviderHealthState state, Instant occurredAt, UUID sourceId) {
    }
}
