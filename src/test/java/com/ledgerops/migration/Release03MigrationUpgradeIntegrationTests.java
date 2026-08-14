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
class Release03MigrationUpgradeIntegrationTests {

    private static final MigrationVersion RELEASE_02_FINAL_VERSION = MigrationVersion.fromVersion("14");
    private static final MigrationVersion CURRENT_RELEASE_VERSION = MigrationVersion.fromVersion("45");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void installsCurrentReleaseFreshAndUpgradesRelease02WithoutLosingTenantData()
            throws Exception {
        createDatabase("ledgerops_release03_fresh");
        createDatabase("ledgerops_release03_upgrade");

        Flyway fresh = flyway("ledgerops_release03_fresh", CURRENT_RELEASE_VERSION);
        assertTrue(fresh.migrate().migrationsExecuted > 0);
        assertEquals(CURRENT_RELEASE_VERSION, fresh.info().current().getVersion());

        Flyway release02 = flyway("ledgerops_release03_upgrade", RELEASE_02_FINAL_VERSION);
        assertEquals(14, release02.migrate().migrationsExecuted);
        UUID tenantId = insertRelease02Evidence("ledgerops_release03_upgrade");

        Flyway current = flyway("ledgerops_release03_upgrade", CURRENT_RELEASE_VERSION);
        assertTrue(current.migrate().migrationsExecuted > 0);
        assertEquals(CURRENT_RELEASE_VERSION, current.info().current().getVersion());

        try (var connection = DriverManager.getConnection(
                databaseUrl("ledgerops_release03_upgrade"), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT count(*) FROM tenancy.tenants WHERE id = '" + tenantId + "'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            try (var tables = statement.executeQuery("""
                    SELECT count(*)
                      FROM information_schema.tables
                     WHERE (table_schema, table_name) IN (
                         ('reporting', 'operational_projection_generation'),
                         ('reporting', 'operational_summary_fact'),
                         ('reporting', 'projection_event')
                     )
                    """)) {
                assertTrue(tables.next());
                assertEquals(3, tables.getInt(1));
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
        return Flyway.configure()
                .dataSource(databaseUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(target)
                .load();
    }

    private UUID insertRelease02Evidence(String database) throws Exception {
        UUID tenantId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                databaseUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     INSERT INTO tenancy.tenants
                         (id, name, default_currency, default_locale, status, created_at, updated_at)
                     VALUES (?, ?, 'SAR', 'en-SA', 'ACTIVE', ?, ?)
                     """)) {
            statement.setObject(1, tenantId);
            statement.setString(2, "Release 0.2 upgrade evidence");
            statement.setTimestamp(3, Timestamp.from(Instant.parse("2026-08-14T00:00:00Z")));
            statement.setTimestamp(4, Timestamp.from(Instant.parse("2026-08-14T00:00:00Z")));
            statement.executeUpdate();
        }
        return tenantId;
    }

    private String databaseUrl(String database) {
        return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)", "/" + database);
    }
}
