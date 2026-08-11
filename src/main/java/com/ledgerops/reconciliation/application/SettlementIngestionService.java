package com.ledgerops.reconciliation.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.reconciliation.api.SettlementBatchSnapshot;
import com.ledgerops.reconciliation.api.SettlementValidationItemSnapshot;
import com.ledgerops.reconciliation.domain.SettlementBatchIdentity;
import com.ledgerops.reconciliation.domain.SettlementRecord;
import com.ledgerops.reconciliation.domain.SettlementRecordValidationException;
import com.ledgerops.reconciliation.domain.SettlementValidationReasonCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class SettlementIngestionService {

    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    public static final String PROVIDER_ID = "SIMULATOR";
    private static final int PERSISTENCE_CHUNK_SIZE = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SettlementBatchStore store;
    private final ObjectStoragePort objectStorage;
    private final SettlementCsvParser csvParser;
    private final SettlementBatchJobLauncher jobLauncher;
    private final AuditAppendPort audit;
    private final MessageOutbox outbox;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public SettlementIngestionService(
            SettlementBatchStore store,
            ObjectStoragePort objectStorage,
            SettlementCsvParser csvParser,
            SettlementBatchJobLauncher jobLauncher,
            AuditAppendPort audit,
            MessageOutbox outbox,
            Clock clock,
            org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        this.store = Objects.requireNonNull(store, "Settlement store must not be null");
        this.objectStorage = Objects.requireNonNull(objectStorage, "Object storage must not be null");
        this.csvParser = Objects.requireNonNull(csvParser, "CSV parser must not be null");
        this.jobLauncher = Objects.requireNonNull(jobLauncher, "Batch job launcher must not be null");
        this.audit = Objects.requireNonNull(audit, "Audit port must not be null");
        this.outbox = Objects.requireNonNull(outbox, "Outbox must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "Transaction manager must not be null"));
    }

    public SettlementBatchSnapshot upload(UploadCommand command) {
        requirePermission(command.context(), command.tenantId(),
                command.context().canUploadSettlements(), "settlement:upload");
        if (command.content() == null) throw new IllegalArgumentException("Settlement file is required");
        if (command.filename() == null || command.filename().isBlank()) {
            throw new IllegalArgumentException("Settlement filename is required");
        }
        Path staged = null;
        try {
            staged = Files.createTempFile("ledgerops-settlement-", ".csv");
            HashAndSize hashAndSize = copyAndHash(command.content(), staged);
            if (hashAndSize.size() > MAX_FILE_BYTES) {
                throw new SettlementStructuralException(
                        SettlementValidationReasonCode.FILE_TOO_LARGE,
                        "Settlement file exceeds the 50 MiB limit");
            }
            String objectKey = ContentAddressedObjectKey.forSettlement(
                    command.tenantId(), hashAndSize.sha256());
            objectStorage.putIfAbsent(objectKey, staged, "text/csv; charset=utf-8");
            SettlementBatchIdentity identity = new SettlementBatchIdentity(
                    command.tenantId(), PROVIDER_ID, command.providerBatchReference(),
                    command.settlementPeriodStart(), command.settlementPeriodEnd());
            UUID requestedBatchVersionId = UUID.randomUUID();
            SettlementBatchSnapshot snapshot = transactionTemplate.execute(status -> {
                SettlementBatchSnapshot inserted = store.insertReceived(
                        requestedBatchVersionId, identity, hashAndSize.sha256(), objectKey,
                        hashAndSize.size(), command.supersedesBatchVersionId(),
                        command.context().applicationUserId(), clock.instant());
                if (!requestedBatchVersionId.equals(inserted.batchVersionId())) {
                    return inserted;
                }
                audit.appendAction(
                        command.actor().issuer(), command.actor().subject(), command.actor().principalType(),
                        command.tenantId(), "reconciliation.settlement-uploaded", "settlement-batch",
                        inserted.batchVersionId().toString(), "Settlement Analyst uploaded a settlement file",
                        evidence(inserted), command.context().correlationId());
                emit(inserted, "RECEIVED", command.context().correlationId());
                return inserted;
            });
            return snapshot;
        } catch (IOException exception) {
            throw new IllegalStateException("Settlement file could not be staged", exception);
        } finally {
            closeQuietly(command.content());
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // Temporary evidence is not authoritative; cleanup can be retried by the OS.
                }
            }
        }
    }

    public SettlementBatchSnapshot validate(
            UUID tenantId,
            UUID batchVersionId,
            AuthorizedRequestContext context,
            AuthenticatedPrincipal actor
    ) {
        requirePermission(context, tenantId, context.canReadReconciliation(), "reconciliation:read");
        SettlementBatchSnapshot batch = requireBatch(tenantId, batchVersionId);
        store.startValidation(tenantId, batchVersionId, clock.instant());
        store.clearValidation(tenantId, batchVersionId);
        long[] counts = {0, 0, 0};
        Map<String, SeenRecord> seen = new HashMap<>();
        List<SettlementBatchStore.OccurrenceDraft> occurrences = new ArrayList<>();
        List<SettlementBatchStore.ValidationItemDraft> validationItems = new ArrayList<>();
        try (InputStream input = objectStorage.open(batch.objectKey())) {
            csvParser.parse(input, (rowNumber, fields) -> {
                counts[0]++;
                SettlementBatchStore.OccurrenceDraft occurrence;
                SettlementBatchStore.ValidationItemDraft validationItem = null;
                try {
                    SettlementRecord record = SettlementRecord.fromFields(fields);
                    if (!batch.providerBatchReference().equals(record.providerBatchReference())
                            || !batch.settlementPeriodStart().equals(record.settlementPeriodStart())
                            || !batch.settlementPeriodEnd().equals(record.settlementPeriodEnd())) {
                        throw new SettlementRecordValidationException(
                                SettlementValidationReasonCode.INCONSISTENT_BATCH_IDENTITY,
                                "Settlement row identity differs from the batch identity");
                    }
                    String hash = record.normalizedContentHash();
                    String duplicateReason = duplicateReason(seen, record.providerRecordKey(), hash);
                    if (duplicateReason != null) {
                        occurrence = quarantineOccurrence(rowNumber, record, duplicateReason);
                        validationItem = validationItem(rowNumber, duplicateReason, record.providerRecordKey());
                        counts[2]++;
                    } else {
                        seen.put(record.providerRecordKey(), new SeenRecord(rowNumber, hash));
                        occurrence = validOccurrence(rowNumber, record);
                        counts[1]++;
                    }
                } catch (SettlementRecordValidationException exception) {
                    if (exception.reasonCode()
                            == SettlementValidationReasonCode.INCONSISTENT_BATCH_IDENTITY) {
                        throw new SettlementStructuralException(
                                exception.reasonCode(), exception.getMessage());
                    }
                    occurrence = invalidOccurrence(rowNumber, fields, exception.reasonCode());
                    validationItem = validationItem(rowNumber, exception.reasonCode().name(),
                            fields.size() > 3 ? fields.get(3) : null);
                    counts[2]++;
                } catch (RuntimeException exception) {
                    occurrence = invalidOccurrence(rowNumber, fields,
                            SettlementValidationReasonCode.INVALID_FIELD);
                    validationItem = validationItem(rowNumber,
                            SettlementValidationReasonCode.INVALID_FIELD.name(),
                            fields.size() > 3 ? fields.get(3) : null);
                    counts[2]++;
                }
                occurrences.add(occurrence);
                if (validationItem != null) validationItems.add(validationItem);
                if (occurrences.size() >= PERSISTENCE_CHUNK_SIZE) {
                    persistChunk(tenantId, batchVersionId, occurrences, validationItems);
                    occurrences.clear();
                    validationItems.clear();
                }
            });
            persistChunk(tenantId, batchVersionId, occurrences, validationItems);
            store.finishValidation(tenantId, batchVersionId,
                    counts[0], counts[1], counts[2], clock.instant());
        } catch (SettlementStructuralException exception) {
            store.failValidation(tenantId, batchVersionId, exception.reasonCode(), clock.instant());
        } catch (IOException exception) {
            store.failValidation(tenantId, batchVersionId,
                    SettlementValidationReasonCode.INVALID_FIELD, clock.instant());
        }
        SettlementBatchSnapshot result = transactionTemplate.execute(status -> {
            SettlementBatchSnapshot current = requireBatch(tenantId, batchVersionId);
            audit.appendAction(actor.issuer(), actor.subject(), actor.principalType(), tenantId,
                    "reconciliation.settlement-validated", "settlement-batch",
                    batchVersionId.toString(), "Settlement file validation completed",
                    evidence(current), context.correlationId());
            emit(current, current.status().name(), context.correlationId());
            return current;
        });
        return result;
    }

    public SettlementBatchSnapshot process(
            UUID tenantId,
            UUID batchVersionId,
            boolean confirmation,
            AuthorizedRequestContext context,
            AuthenticatedPrincipal actor
    ) {
        requirePermission(context, tenantId, context.canRunReconciliation(), "reconciliation:run");
        if (!confirmation) throw new IllegalArgumentException("Explicit processing confirmation is required");
        SettlementBatchSnapshot batch = requireBatch(tenantId, batchVersionId);
        store.startProcessing(tenantId, batchVersionId, clock.instant());
        try {
            jobLauncher.launch(tenantId, batchVersionId);
        } catch (RuntimeException exception) {
            store.failProcessing(tenantId, batchVersionId, clock.instant());
            throw exception;
        }
        SettlementBatchSnapshot result = transactionTemplate.execute(status -> {
            SettlementBatchSnapshot current = requireBatch(tenantId, batchVersionId);
            audit.appendAction(actor.issuer(), actor.subject(), actor.principalType(), tenantId,
                    "reconciliation.settlement-processing-confirmed", "settlement-batch",
                    batchVersionId.toString(), "Reconciliation Analyst confirmed settlement processing",
                    evidence(current), context.correlationId());
            emit(current, current.status().name(), context.correlationId());
            return current;
        });
        return result;
    }

    public List<SettlementBatchSnapshot> list(UUID tenantId, AuthorizedRequestContext context) {
        requirePermission(context, tenantId, context.canReadReconciliation(), "reconciliation:read");
        return store.findByTenant(tenantId);
    }

    public SettlementBatchSnapshot find(UUID tenantId, UUID batchVersionId,
                                        AuthorizedRequestContext context) {
        requirePermission(context, tenantId, context.canReadReconciliation(), "reconciliation:read");
        return requireBatch(tenantId, batchVersionId);
    }

    public List<SettlementValidationItemSnapshot> validationItems(
            UUID tenantId, UUID batchVersionId, AuthorizedRequestContext context) {
        requirePermission(context, tenantId, context.canReadReconciliation(), "reconciliation:read");
        requireBatch(tenantId, batchVersionId);
        return store.validationItems(tenantId, batchVersionId);
    }

    private void persistChunk(UUID tenantId, UUID batchVersionId,
                              List<SettlementBatchStore.OccurrenceDraft> occurrences,
                              List<SettlementBatchStore.ValidationItemDraft> validationItems) {
        if (!occurrences.isEmpty()) {
            store.persistValidationChunk(tenantId, batchVersionId,
                    List.copyOf(occurrences), List.copyOf(validationItems));
        }
    }

    private String duplicateReason(Map<String, SeenRecord> seen, String key, String hash) {
        SeenRecord previous = seen.get(key);
        if (previous == null) return null;
        return previous.hash().equals(hash)
                ? SettlementValidationReasonCode.DUPLICATE_PROVIDER_RECORD.name()
                : SettlementValidationReasonCode.CONFLICTING_PROVIDER_RECORD.name();
    }

    private SettlementBatchStore.OccurrenceDraft validOccurrence(long rowNumber, SettlementRecord record) {
        return new SettlementBatchStore.OccurrenceDraft(
                rowNumber, record.providerRecordKey(), record.normalizedContentHash(),
                record.normalizedContentJson(), "VALID", null, UUID.randomUUID(), null);
    }

    private SettlementBatchStore.OccurrenceDraft quarantineOccurrence(
            long rowNumber, SettlementRecord record, String reason) {
        return new SettlementBatchStore.OccurrenceDraft(
                rowNumber, record.providerRecordKey(), record.normalizedContentHash(),
                record.normalizedContentJson(), "QUARANTINED", reason, UUID.randomUUID(), null);
    }

    private SettlementBatchStore.OccurrenceDraft invalidOccurrence(
            long rowNumber, List<String> fields, SettlementValidationReasonCode reasonCode) {
        return new SettlementBatchStore.OccurrenceDraft(
                rowNumber, fields.size() > 3 ? safe(fields.get(3)) : "row-" + rowNumber,
                null, safeEvidence(fields), "QUARANTINED", reasonCode.name(), UUID.randomUUID(), null);
    }

    private SettlementBatchStore.ValidationItemDraft validationItem(
            long rowNumber, String reasonCode, String providerRecordKey) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("providerRecordKey", providerRecordKey == null ? "" : safe(providerRecordKey));
        evidence.put("rowNumber", rowNumber);
        return new SettlementBatchStore.ValidationItemDraft(
                rowNumber, reasonCode, writeJson(evidence), UUID.randomUUID(), clock.instant());
    }

    private String safeEvidence(List<String> fields) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("fieldCount", fields.size());
        evidence.put("fields", fields.stream().map(this::safe).toList());
        return writeJson(evidence);
    }

    private String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Settlement evidence could not be encoded", exception);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.length() > 120 ? value.substring(0, 120) : value;
    }

    private SettlementBatchSnapshot requireBatch(UUID tenantId, UUID batchVersionId) {
        return store.findById(tenantId, batchVersionId)
                .orElseThrow(AuthorizationResourceNotFoundException::new);
    }

    private void requirePermission(AuthorizedRequestContext context, UUID tenantId,
                                   boolean allowed, String permission) {
        if (context == null || !tenantId.equals(context.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!allowed || !context.isTenantWide()) {
            throw new AuthorizationPermissionDeniedException(permission);
        }
    }

    private String evidence(SettlementBatchSnapshot snapshot) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("batchVersionId", snapshot.batchVersionId());
        details.put("familyId", snapshot.familyId());
        details.put("sha256", snapshot.rawFileSha256());
        details.put("status", snapshot.status().name());
        details.put("totalRows", snapshot.totalRows());
        details.put("validRows", snapshot.validRows());
        details.put("invalidRows", snapshot.invalidRows());
        return writeJson(details);
    }

    private void emit(SettlementBatchSnapshot snapshot, String status, String correlationId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("batchVersionId", snapshot.batchVersionId());
            payload.put("familyId", snapshot.familyId());
            payload.put("tenantId", snapshot.tenantId());
            payload.put("providerId", snapshot.providerId());
            payload.put("status", status);
            payload.put("totalRows", snapshot.totalRows());
            payload.put("validRows", snapshot.validRows());
            payload.put("invalidRows", snapshot.invalidRows());
            UUID eventId = snapshot.batchVersionId();
            outbox.appendOrGet(new OutboxMessageDraft(
                    ProducerName.RECONCILIATION,
                    "settlement-batch:" + snapshot.batchVersionId() + ":" + status,
                    "SettlementBatchStatusChanged", 1, eventId, snapshot.tenantId(),
                    "ledgerops.reconciliation.lifecycle.v1", snapshot.familyId().toString(),
                    JSON.writeValueAsString(payload),
                    correlationId == null ? eventId : UUID.fromString(correlationId),
                    eventId, clock.instant()));
        } catch (Exception exception) {
            throw new IllegalStateException("Settlement lifecycle event could not be encoded", exception);
        }
    }

    private HashAndSize copyAndHash(InputStream input, Path target) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try {
            byte[] buffer = new byte[8192];
            long size = 0;
            try (var output = Files.newOutputStream(target)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > MAX_FILE_BYTES) {
                        // Continue neither hashing nor writing after the contractual limit.
                        throw new SettlementStructuralException(
                                SettlementValidationReasonCode.FILE_TOO_LARGE,
                                "Settlement file exceeds the 50 MiB limit");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            return new HashAndSize(java.util.HexFormat.of().formatHex(digest.digest()), size);
        } finally {
            closeQuietly(input);
        }
    }

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The staged file and object store remain the authoritative recovery boundary.
        }
    }

    public record UploadCommand(
            UUID tenantId,
            String providerBatchReference,
            java.time.LocalDate settlementPeriodStart,
            java.time.LocalDate settlementPeriodEnd,
            String filename,
            String contentType,
            InputStream content,
            UUID supersedesBatchVersionId,
            AuthorizedRequestContext context,
            AuthenticatedPrincipal actor
    ) {
    }

    private record HashAndSize(String sha256, long size) {
    }

    private record SeenRecord(long rowNumber, String hash) {
    }
}
