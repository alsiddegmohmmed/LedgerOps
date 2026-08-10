package com.ledgerops.identity.api;

public interface InvitationAdministrationPort {

    InvitationAdministrationResult create(InvitationAdministrationCommand command);

    InvitationAdministrationResult reinvite(InvitationAdministrationCommand command);

    MembershipRoleMutationResult replaceRoles(MembershipRoleMutationCommand command);
}
