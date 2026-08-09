package com.ledgerops.tenancy.domain;

import java.util.Optional;
import java.util.UUID;

public interface OperationalContactRepository {

    long nextVersion(TenantId tenantId, UUID contactId);

    void append(OperationalContact contact);

    Optional<OperationalContact> current(TenantId tenantId, UUID contactId);
}
