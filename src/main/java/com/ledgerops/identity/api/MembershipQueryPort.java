package com.ledgerops.identity.api;

import java.util.List;
import java.util.UUID;

public interface MembershipQueryPort {

    List<MembershipResponse> current(
            UUID tenantId,
            MembershipReadAuthorization authorization
    );

    MembershipResponse current(
            UUID tenantId,
            UUID membershipId,
            MembershipReadAuthorization authorization
    );
}
