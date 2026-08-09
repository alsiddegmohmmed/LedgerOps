package com.ledgerops.identity.domain;

import java.util.Optional;

public interface InvitationRepository {

    Invitation save(Invitation invitation, TenantMembershipId membershipId);

    Optional<Invitation> findById(InvitationId invitationId);

    Optional<TenantMembershipId> findMembershipId(InvitationId invitationId);

    Optional<Invitation> findPendingByTokenHash(InvitationTokenHash tokenHash);

    Optional<Invitation> findPendingByTokenHashForUpdate(InvitationTokenHash tokenHash);
}
