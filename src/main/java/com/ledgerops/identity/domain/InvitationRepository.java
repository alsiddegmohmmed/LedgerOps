package com.ledgerops.identity.domain;

import java.util.Optional;

public interface InvitationRepository {

    Invitation save(Invitation invitation, TenantMembershipId membershipId);

    Optional<Invitation> findById(InvitationId invitationId);

    Optional<Invitation> findByMembershipId(TenantMembershipId membershipId);

    Optional<Invitation> findByMembershipIdForUpdate(TenantMembershipId membershipId);

    Optional<TenantMembershipId> findMembershipId(InvitationId invitationId);

    Optional<Invitation> findPendingByTokenHash(InvitationTokenHash tokenHash);

    Optional<Invitation> findPendingByTokenHashForUpdate(InvitationTokenHash tokenHash);
}
