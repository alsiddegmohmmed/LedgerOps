package com.ledgerops.messaging.api;

import java.util.Optional;
import java.util.UUID;

public interface MessageOutbox {

    StoredOutboxMessage appendOrGet(OutboxMessageDraft draft);

    Optional<StoredOutboxMessage> find(
            ProducerName producerName,
            String deduplicationKey
    );

    Optional<StoredOutboxMessage> findByAggregate(
            ProducerName producerName,
            String messageType,
            UUID tenantId,
            UUID aggregateId
    );

    StoredOutboxMessage requireExistingEquivalent(OutboxMessageDraft draft);
}
