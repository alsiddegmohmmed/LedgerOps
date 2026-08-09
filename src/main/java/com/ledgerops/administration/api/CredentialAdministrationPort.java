package com.ledgerops.administration.api;

public interface CredentialAdministrationPort {

    CredentialProvisioningResult provision(CredentialProvisioningCommand command);

    CredentialRotationResult rotate(CredentialRotationCommand command);

    CredentialRevocationResult revoke(CredentialRevocationCommand command);
}
