package com.ledgerops.demo;

import com.ledgerops.reporting.api.OperationalSummaryProjectionRebuildUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Local-only maintenance entry point for rebuilding the approved Reporting
 * projection from module-owned source query boundaries. It is not an HTTP API.
 */
@Component
@Profile("demo")
final class LocalOperationalSummaryRebuildRunner implements ApplicationRunner {

    private static final String TENANT_PROPERTY = "LEDGEROPS_DEMO_REBUILD_TENANT_ID";
    private static final String FROM_PROPERTY = "LEDGEROPS_DEMO_REBUILD_FROM";
    private static final String TO_PROPERTY = "LEDGEROPS_DEMO_REBUILD_TO";
    private static final String AS_OF_PROPERTY = "LEDGEROPS_DEMO_REBUILD_AS_OF";

    private final Environment environment;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final OperationalSummaryProjectionRebuildUseCase rebuildService;
    private final ConfigurableApplicationContext context;

    LocalOperationalSummaryRebuildRunner(
            Environment environment,
            Clock clock,
            JdbcTemplate jdbc,
            OperationalSummaryProjectionRebuildUseCase rebuildService,
            ConfigurableApplicationContext context
    ) {
        this.environment = environment;
        this.clock = clock;
        this.jdbc = jdbc;
        this.rebuildService = rebuildService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = UUID.fromString(required(TENANT_PROPERTY));
        Instant asOf = optionalInstant(AS_OF_PROPERTY, clock.instant());
        Instant from = optionalInstant(FROM_PROPERTY, asOf.minus(Duration.ofDays(30)));
        Instant to = optionalInstant(TO_PROPERTY, asOf.plus(Duration.ofDays(1)));
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("Demo rebuild period must start before it ends");
        }

        Long cursor = jdbc.queryForObject(
                "SELECT coalesce(max(event_id), 0) FROM reporting.projection_event WHERE tenant_id = ?",
                Long.class,
                tenantId);
        rebuildService.rebuild(tenantId, from, to, asOf, cursor == null ? 0L : cursor);
        System.out.printf(
                "Rebuilt operational Reporting projection for tenant %s from %s to %s at %s%n",
                tenantId, from, to, asOf);
        SpringApplication.exit(context, () -> 0);
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required local demo property " + name);
        }
        return value;
    }

    private Instant optionalInstant(String name, Instant defaultValue) {
        String value = environment.getProperty(name);
        return value == null || value.isBlank() ? defaultValue : Instant.parse(value);
    }
}
