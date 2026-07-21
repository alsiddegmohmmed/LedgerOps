package com.ledgerops.ledger.infrastructure;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
class FinancialEffectMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicInteger duplicateSourceCount = new AtomicInteger();

    FinancialEffectMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        FunctionCounter.builder(
                        "ledgerops.duplicate.financial.effect",
                        duplicateSourceCount,
                        AtomicInteger::get
                )
                .description("Duplicate PAYMENT-source financial identities detected; target zero")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${ledgerops.financial-effect.metrics.delay-ms:5000}")
    void refresh() {
        Integer detected = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT tenant_id, source_type, source_id
                      FROM ledger.transactions
                     WHERE source_type = 'PAYMENT'
                     GROUP BY tenant_id, source_type, source_id
                    HAVING count(*) > 1
                ) duplicate_sources
                """, Integer.class);
        duplicateSourceCount.set(detected == null ? 0 : detected);
    }
}
