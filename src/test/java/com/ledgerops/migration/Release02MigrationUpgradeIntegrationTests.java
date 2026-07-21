package com.ledgerops.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class Release02MigrationUpgradeIntegrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void installsRelease02FreshAndUpgradesTheRelease01V7BaselineWithoutLosingData()
            throws Exception {
        createDatabase("ledgerops_fresh");
        createDatabase("ledgerops_upgrade");

        Flyway fresh = flyway("ledgerops_fresh", null);
        assertEquals(14, fresh.migrate().migrationsExecuted);
        assertEquals("14", fresh.info().current().getVersion().getVersion());

        Flyway release01 = flyway("ledgerops_upgrade", MigrationVersion.fromVersion("7"));
        assertEquals(7, release01.migrate().migrationsExecuted);
        UUID tenantId = insertRelease01Evidence("ledgerops_upgrade");

        Flyway release02 = flyway("ledgerops_upgrade", null);
        assertEquals(7, release02.migrate().migrationsExecuted);
        assertEquals("14", release02.info().current().getVersion().getVersion());

        try (var connection = DriverManager.getConnection(
                databaseUrl("ledgerops_upgrade"), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT count(*) FROM tenancy.tenants WHERE id = '" + tenantId + "'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            try (var columns = statement.executeQuery("""
                    SELECT count(*)
                      FROM information_schema.columns
                     WHERE (table_schema, table_name, column_name) IN (
                         ('messaging', 'outbox', 'traceparent'),
                         ('provider', 'work', 'traceparent'),
                         ('provider', 'webhook_events', 'traceparent')
                     )
                    """)) {
                assertTrue(columns.next());
                assertEquals(3, columns.getInt(1));
            }
        }
    }

    private void createDatabase(String name) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
    }

    private Flyway flyway(String database, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(databaseUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private UUID insertRelease01Evidence(String database) throws Exception {
        UUID tenantId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                databaseUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     INSERT INTO tenancy.tenants
                         (id, name, default_currency, default_locale, status, created_at, updated_at)
                     VALUES (?, ?, 'SAR', 'en-SA', 'ACTIVE', ?, ?)
                     """)) {
            statement.setObject(1, tenantId);
            statement.setString(2, "Release 0.1 upgrade evidence");
            statement.setTimestamp(3, Timestamp.from(Instant.parse("2026-07-21T00:00:00Z")));
            statement.setTimestamp(4, Timestamp.from(Instant.parse("2026-07-21T00:00:00Z")));
            statement.executeUpdate();
        }
        return tenantId;
    }

    private String databaseUrl(String database) {
        return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
    }
}
