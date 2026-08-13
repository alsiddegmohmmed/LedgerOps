package com.ledgerops.reporting.infrastructure;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OperationalSummarySchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsTheGenerationAndFactTablesWithoutSourceForeignKeys() {
        List<String> tables = jdbc.queryForList(
                """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'reporting'
                   AND table_name IN (
                       'operational_projection_generation',
                       'operational_projection_current',
                       'operational_summary_fact',
                       'projection_event'
                   )
                 ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactly(
                "operational_projection_current",
                "operational_projection_generation",
                "operational_summary_fact",
                "projection_event");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM pg_class sequence
                  JOIN pg_namespace namespace ON namespace.oid = sequence.relnamespace
                 WHERE namespace.nspname = 'reporting'
                   AND sequence.relname = 'operational_projection_generation_seq'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM pg_class sequence
                  JOIN pg_namespace namespace ON namespace.oid = sequence.relnamespace
                 WHERE namespace.nspname = 'reporting'
                   AND sequence.relname = 'projection_event_id_seq'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void factForeignKeysStayInsideReporting() {
        List<String> referencedSchemas = jdbc.queryForList(
                """
                SELECT DISTINCT target_namespace.nspname
                  FROM pg_constraint constraint_record
                  JOIN pg_class source_table
                    ON source_table.oid = constraint_record.conrelid
                  JOIN pg_namespace source_namespace
                    ON source_namespace.oid = source_table.relnamespace
                  JOIN pg_class target_table
                    ON target_table.oid = constraint_record.confrelid
                  JOIN pg_namespace target_namespace
                    ON target_namespace.oid = target_table.relnamespace
                 WHERE source_namespace.nspname = 'reporting'
                   AND source_table.relname IN (
                       'operational_projection_generation',
                       'operational_projection_current',
                       'operational_summary_fact',
                       'projection_event'
                   )
                   AND constraint_record.contype = 'f'
                 ORDER BY target_namespace.nspname
                """, String.class);

        assertThat(referencedSchemas).containsOnly("reporting");
    }
}
