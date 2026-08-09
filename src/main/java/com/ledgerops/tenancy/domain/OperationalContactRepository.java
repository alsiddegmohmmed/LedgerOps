package com.ledgerops.tenancy.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface OperationalContactRepository {

    long nextVersion(TenantId tenantId, UUID contactId);

    void append(OperationalContact contact);

    Optional<OperationalContact> current(TenantId tenantId, UUID contactId);

    List<OperationalContact> currentAll(TenantId tenantId);
}
