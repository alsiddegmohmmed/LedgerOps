package com.ledgerops.tenancy.infrastructure;

import com.ledgerops.tenancy.domain.TenantConfiguration;
import com.ledgerops.tenancy.domain.TenantConfigurationRepository;
import com.ledgerops.tenancy.domain.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class TenantConfigurationPersistenceAdapter implements TenantConfigurationRepository {

    private final JdbcTemplate jdbc;

    TenantConfigurationPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC template must not be null");
    }

    @Override
    public long nextVersion(TenantId tenantId) {
        Long next = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(version), 0) + 1
                  FROM tenancy.tenant_configurations
                 WHERE tenant_id = ?
                """,
                Long.class,
                tenantId.value()
        );
        return Objects.requireNonNull(next, "Configuration version query returned null");
    }

    @Override
    public void append(TenantConfiguration configuration) {
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO tenancy.tenant_configurations (
                        tenant_id, version, allowed_currencies, default_locale,
                        timezone, display_settings, created_at, actor_identity
                    ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """);
            statement.setObject(1, configuration.tenantId().value());
            statement.setLong(2, configuration.version());
            Array currencies = connection.createArrayOf(
                    "text",
                    configuration.allowedCurrencies().stream()
                            .map(Currency::getCurrencyCode)
                            .sorted()
                            .toArray(String[]::new)
            );
            statement.setArray(3, currencies);
            statement.setString(4, configuration.defaultLocale().toLanguageTag());
            statement.setString(5, configuration.timezone().getId());
            statement.setString(6, configuration.displaySettingsJson());
            statement.setTimestamp(7, Timestamp.from(configuration.createdAt()));
            statement.setString(8, configuration.actorIdentity());
            return statement;
        });
    }

    @Override
    public Optional<TenantConfiguration> current(TenantId tenantId) {
        return jdbc.query(
                        """
                        SELECT tenant_id, version, allowed_currencies, default_locale,
                               timezone, display_settings::text AS display_settings,
                               created_at, actor_identity
                          FROM tenancy.tenant_configurations
                         WHERE tenant_id = ?
                         ORDER BY version DESC
                         LIMIT 1
                        """,
                        this::map,
                        tenantId.value()
                )
                .stream()
                .findFirst();
    }

    private TenantConfiguration map(ResultSet resultSet, int rowNumber) throws SQLException {
        Array sqlCurrencies = resultSet.getArray("allowed_currencies");
        String[] currencies = (String[]) sqlCurrencies.getArray();
        java.util.Set<Currency> allowedCurrencies = new java.util.LinkedHashSet<>();
        for (String currency : currencies) {
            allowedCurrencies.add(Currency.getInstance(currency));
        }
        return new TenantConfiguration(
                TenantId.from(resultSet.getObject("tenant_id", UUID.class)),
                resultSet.getLong("version"),
                allowedCurrencies,
                Locale.forLanguageTag(resultSet.getString("default_locale")),
                ZoneId.of(resultSet.getString("timezone")),
                resultSet.getString("display_settings"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getString("actor_identity")
        );
    }
}
