package com.ledgerops.contracts;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseObservabilityContractTests {

    private static final Path MESSAGING_DASHBOARD = Path.of(
            "observability/grafana/dashboards/release-0.2-messaging.json");
    private static final Path PROVIDER_DASHBOARD = Path.of(
            "observability/grafana/dashboards/release-0.2-provider.json");

    @Test
    void dashboardsAreValidAndCoverTheReleaseSignalsWithoutHighCardinalityLabels()
            throws Exception {
        var json = JsonMapper.builder().build();
        Set<String> expressions = new java.util.HashSet<>();
        for (Path dashboard : Set.of(MESSAGING_DASHBOARD, PROVIDER_DASHBOARD)) {
            var root = json.readTree(Files.readString(dashboard));
            root.required("panels").forEach(panel -> panel.required("targets")
                    .forEach(target -> expressions.add(target.required("expr").asString())));
        }

        assertTrue(expressions.stream().anyMatch(value -> value.contains("ledgerops_outbox_pending")));
        assertTrue(expressions.stream().anyMatch(value -> value.contains("ledgerops_kafka_consumer_lag")));
        assertTrue(expressions.stream().anyMatch(value -> value.contains("ledgerops_provider_http_duration_seconds")));
        assertTrue(expressions.stream().anyMatch(value -> value.contains("ledgerops_webhook_receipt_total")));
        assertTrue(expressions.stream().anyMatch(value -> value.contains("ledgerops_duplicate_financial_effect_total")));

        String dashboard = Files.readString(MESSAGING_DASHBOARD)
                + Files.readString(PROVIDER_DASHBOARD);
        for (String prohibited : Set.of("tenantId", "paymentId", "attemptId", "messageId",
                "providerReference", "correlationId", "traceId")) {
            assertFalse(dashboard.contains("{{" + prohibited + "}}"));
        }
    }

    @Test
    void prometheusScrapesOnlyTheTwoReleaseApplications() throws Exception {
        String config = Files.readString(Path.of(
                "observability/prometheus/prometheus.yml"));
        assertTrue(config.contains("host.docker.internal:8080"));
        assertTrue(config.contains("host.docker.internal:8081"));
        assertTrue(config.contains("/actuator/prometheus"));
        assertTrue(config.contains("release-0.2-alerts.yml"));

        String alerts = Files.readString(Path.of(
                "observability/prometheus/release-0.2-alerts.yml"));
        for (String required : Set.of("LedgerOpsOutboxBacklog", "LedgerOpsConsumerLag",
                "LedgerOpsFinancialDeadLetter", "LedgerOpsProviderUnavailable",
                "LedgerOpsProviderAmbiguity", "LedgerOpsWebhookBacklog",
                "LedgerOpsStuckPayment", "LedgerOpsDuplicateFinancialEffect")) {
            assertTrue(alerts.contains("alert: " + required));
        }
        assertTrue(alerts.contains("docs/runbooks/release-0.2-operations.md"));
    }
}
