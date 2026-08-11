package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.reconciliation.application.SettlementBatchJobLauncher;
import com.ledgerops.reconciliation.application.SettlementBatchStore;
import com.ledgerops.reconciliation.application.SettlementBatchStore.SettlementOccurrenceRow;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
class SettlementBatchJobConfiguration {

    static final int CHUNK_SIZE = 500;

    @Bean
    Job settlementIngestionJob(JobRepository jobRepository, Step settlementCanonicalizationStep,
                               JobExecutionListener settlementJobListener) {
        return new JobBuilder("settlementIngestionJob", jobRepository)
                .start(settlementCanonicalizationStep)
                .listener(settlementJobListener)
                .build();
    }

    @Bean
    Step settlementCanonicalizationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<SettlementOccurrenceRow> settlementOccurrenceReader,
            ItemWriter<SettlementOccurrenceRow> settlementCanonicalWriter
    ) {
        return new StepBuilder("settlementCanonicalizationStep", jobRepository)
                .<SettlementOccurrenceRow, SettlementOccurrenceRow>chunk(CHUNK_SIZE, transactionManager)
                .reader(settlementOccurrenceReader)
                .writer(settlementCanonicalWriter)
                .build();
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<SettlementOccurrenceRow> settlementOccurrenceReader(
            DataSource dataSource,
            @Value("#{jobParameters['batchVersionId']}") String batchVersionId
    ) throws Exception {
        PostgresPagingQueryProvider provider = new PostgresPagingQueryProvider();
        provider.setSelectClause("select occurrence_id, batch_version_id, tenant_id, row_number, "
                + "provider_record_key, normalized_content_hash, normalized_content::text");
        provider.setFromClause("from reconciliation.settlement_record_occurrences");
        provider.setWhereClause("where batch_version_id = :batchVersionId and validation_state = 'VALID'");
        provider.setSortKeys(Map.of("row_number", Order.ASCENDING));
        return new JdbcPagingItemReaderBuilder<SettlementOccurrenceRow>()
                .name("settlementOccurrenceReader")
                .dataSource(dataSource)
                .queryProvider(provider)
                .parameterValues(Map.of("batchVersionId", UUID.fromString(batchVersionId)))
                .pageSize(CHUNK_SIZE)
                .fetchSize(CHUNK_SIZE)
                .rowMapper(SettlementBatchJobConfiguration::mapOccurrence)
                .saveState(true)
                .build();
    }

    @Bean
    @StepScope
    ItemWriter<SettlementOccurrenceRow> settlementCanonicalWriter(
            SettlementBatchStore store,
            Clock clock,
            @Value("#{jobParameters['batchVersionId']}") String batchVersionId
    ) {
        UUID id = UUID.fromString(batchVersionId);
        return (Chunk<? extends SettlementOccurrenceRow> chunk) -> {
            if (!chunk.isEmpty()) {
                store.persistCanonicalChunk(id, new ArrayList<>(chunk.getItems()), clock.instant());
            }
        };
    }

    @Bean
    JobExecutionListener settlementJobListener(SettlementBatchStore store, Clock clock) {
        return new JobExecutionListener() {
            @Override
            public void afterJob(JobExecution jobExecution) {
                String batchVersionId = jobExecution.getJobParameters().getString("batchVersionId");
                String tenantId = jobExecution.getJobParameters().getString("tenantId");
                if (batchVersionId == null || tenantId == null) {
                    throw new IllegalStateException("Settlement job is missing tenant and batch parameters");
                }
                UUID tenant = UUID.fromString(tenantId);
                UUID batch = UUID.fromString(batchVersionId);
                if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
                    store.finishProcessing(tenant, batch, clock.instant());
                } else {
                    store.failProcessing(tenant, batch, clock.instant());
                }
            }
        };
    }

    @Bean
    SettlementBatchJobLauncher settlementBatchJobLauncher(JobLauncher jobLauncher, Job settlementIngestionJob) {
        return (tenantId, batchVersionId) -> {
            try {
                JobExecution execution = jobLauncher.run(
                        settlementIngestionJob,
                        new JobParametersBuilder()
                                .addString("tenantId", tenantId.toString(), true)
                                .addString("batchVersionId", batchVersionId.toString(), true)
                                .toJobParameters());
                if (execution.getStatus() != BatchStatus.COMPLETED) {
                    throw new IllegalStateException(
                            "Settlement canonicalization did not complete: " + execution.getStatus());
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Settlement canonicalization could not be launched", exception);
            }
        };
    }

    private static SettlementOccurrenceRow mapOccurrence(ResultSet rs, int rowNumber) throws SQLException {
        return new SettlementOccurrenceRow(
                rs.getObject("occurrence_id", UUID.class),
                rs.getObject("batch_version_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getLong("row_number"),
                rs.getString("provider_record_key"),
                rs.getString("normalized_content_hash"),
                rs.getString("normalized_content"));
    }
}
