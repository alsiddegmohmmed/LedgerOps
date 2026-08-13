package com.ledgerops.reporting.application;

import com.ledgerops.reporting.api.OperationalSummaryMetricCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class OperationalSummaryCursorCodec {

    private static final String VERSION = "v1";

    private OperationalSummaryCursorCodec() {
    }

    public static String encode(
            OperationalSummaryMetricCode metric,
            Instant from,
            Instant to,
            Set<UUID> merchantIds,
            Instant occurredAt,
            UUID sourceId
    ) {
        String merchants = merchantIds.stream()
                .map(UUID::toString)
                .sorted()
                .collect(Collectors.joining(","));
        String value = String.join("\n", VERSION, metric.name(), from.toString(), to.toString(),
                merchants, occurredAt.toString(), sourceId.toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static Position decode(
            String encoded,
            OperationalSummaryMetricCode metric,
            Instant from,
            Instant to,
            Set<UUID> merchantIds
    ) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split("\\n", -1);
            if (parts.length != 7 || !VERSION.equals(parts[0])
                    || !metric.name().equals(parts[1])
                    || !from.toString().equals(parts[2])
                    || !to.toString().equals(parts[3])
                    || !parts[4].equals(normalizedMerchantSet(merchantIds))) {
                throw invalid();
            }
            return new Position(Instant.parse(parts[5]), UUID.fromString(parts[6]));
        } catch (RuntimeException exception) {
            if (exception instanceof InvalidOperationalSummaryCursorException) {
                throw exception;
            }
            throw invalid();
        }
    }

    private static String normalizedMerchantSet(Set<UUID> merchantIds) {
        return merchantIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
    }

    private static InvalidOperationalSummaryCursorException invalid() {
        return new InvalidOperationalSummaryCursorException(
                "The operational-summary cursor is malformed or does not match the requested filters");
    }

    public record Position(Instant occurredAt, UUID sourceId) {
    }
}
