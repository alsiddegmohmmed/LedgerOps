package com.ledgerops.administration.application;

import com.ledgerops.administration.api.CredentialAdministrationPort;
import com.ledgerops.administration.api.CredentialProvisioningCommand;
import com.ledgerops.administration.api.CredentialProvisioningResult;
import com.ledgerops.administration.api.CredentialRevocationCommand;
import com.ledgerops.administration.api.CredentialRevocationResult;
import com.ledgerops.administration.api.CredentialRotationCommand;
import com.ledgerops.administration.api.CredentialRotationResult;
import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.ServiceCredentialMetadata;
import com.ledgerops.identity.api.ServiceCredentialProvisioningPort;
import com.ledgerops.identity.api.ServiceCredentialProvisioningRequest;
import com.ledgerops.identity.api.ServiceCredentialProvisioningResult;
import com.ledgerops.identity.api.ServiceCredentialQueryPort;
import com.ledgerops.identity.api.ServiceCredentialRevocationPort;
import com.ledgerops.identity.api.ServiceCredentialRevocationResult;
import com.ledgerops.merchant.api.MerchantActivityQuery;
import com.ledgerops.merchant.api.MerchantActivityStatus;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.tenancy.api.TenantActivityQuery;
import com.ledgerops.tenancy.api.TenantActivityStatus;
import com.ledgerops.tenancy.api.TenantReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service("administrationCredentialService")
@ConditionalOnBean({
        ServiceCredentialProvisioningPort.class,
        ServiceCredentialRevocationPort.class
})
class CredentialAdministrationService implements CredentialAdministrationPort {

    private final ServiceCredentialProvisioningPort provisioning;
    private final ServiceCredentialRevocationPort revocation;
    private final ServiceCredentialQueryPort queries;
    private final TenantActivityQuery tenants;
    private final MerchantActivityQuery merchants;
    private final AuditAppendPort audit;

    CredentialAdministrationService(
            ServiceCredentialProvisioningPort provisioning,
            ServiceCredentialRevocationPort revocation,
            ServiceCredentialQueryPort queries,
            TenantActivityQuery tenants,
            MerchantActivityQuery merchants,
            AuditAppendPort audit
    ) {
        this.provisioning = Objects.requireNonNull(provisioning);
        this.revocation = Objects.requireNonNull(revocation);
        this.queries = Objects.requireNonNull(queries);
        this.tenants = Objects.requireNonNull(tenants);
        this.merchants = Objects.requireNonNull(merchants);
        this.audit = Objects.requireNonNull(audit);
    }

    @Override
    public CredentialProvisioningResult provision(CredentialProvisioningCommand command) {
        requireConfirmed(command.confirmation());
        requireActiveTarget(
                command.tenantId(),
                command.merchantId(),
                command.authorization()
        );

        ServiceCredentialProvisioningResult result = provisioning.provision(
                new ServiceCredentialProvisioningRequest(
                        command.tenantId(),
                        command.merchantId(),
                        command.label(),
                        command.authorization().applicationUserId(),
                        null
                )
        );
        audit.appendCredentialProvisioned(
                command.actor().issuer(),
                command.actor().subject(),
                command.tenantId(),
                command.merchantId(),
                result.credentialId(),
                result.operationId(),
                command.reason(),
                command.authorization().correlationId()
        );
        return new CredentialProvisioningResult(
                result.credentialId(),
                result.operationId(),
                result.tenantId(),
                result.merchantId(),
                result.keycloakClientId(),
                result.clientSecret(),
                "ACTIVE"
        );
    }

    @Override
    public CredentialRotationResult rotate(CredentialRotationCommand command) {
        requireConfirmed(command.confirmation());
        ServiceCredentialMetadata target = target(command.credentialId());
        requireTargetAccess(target, command.tenantId(), command.authorization());
        requireActiveTarget(target.tenantId(), target.merchantId(), command.authorization());

        ServiceCredentialProvisioningResult result = provisioning.rotate(
                command.credentialId());
        audit.appendCredentialRotated(
                command.actor().issuer(),
                command.actor().subject(),
                command.tenantId(),
                target.merchantId(),
                target.credentialId(),
                result.credentialId(),
                command.reason(),
                command.authorization().correlationId()
        );
        return new CredentialRotationResult(
                target.credentialId(),
                result.credentialId(),
                result.operationId(),
                result.tenantId(),
                result.merchantId(),
                result.keycloakClientId(),
                result.clientSecret(),
                "ACTIVE"
        );
    }

    @Override
    public CredentialRevocationResult revoke(CredentialRevocationCommand command) {
        requireConfirmed(command.confirmation());
        ServiceCredentialMetadata target = target(command.credentialId());
        requireTargetAccess(target, command.tenantId(), command.authorization());

        ServiceCredentialRevocationResult result = revocation.revoke(
                command.credentialId());
        audit.appendCredentialRevoked(
                command.actor().issuer(),
                command.actor().subject(),
                command.tenantId(),
                target.merchantId(),
                result.credentialId(),
                command.reason(),
                command.authorization().correlationId()
        );
        return new CredentialRevocationResult(
                result.credentialId(),
                result.operationId(),
                result.tenantId(),
                result.merchantId(),
                result.keycloakClientId(),
                "REVOKED"
        );
    }

    private ServiceCredentialMetadata target(UUID credentialId) {
        return queries.find(credentialId)
                .orElseThrow(AuthorizationResourceNotFoundException::new);
    }

    private void requireTargetAccess(
            ServiceCredentialMetadata target,
            UUID routeTenantId,
            AuthorizedRequestContext authorization
    ) {
        if (!target.tenantId().equals(routeTenantId)) {
            throw new AuthorizationResourceNotFoundException();
        }
        requireCredentialPermission(authorization);
        if (!authorization.tenantId().equals(target.tenantId())
                || !authorization.allowsMerchant(target.merchantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
    }

    private void requireActiveTarget(
            UUID tenantId,
            UUID merchantId,
            AuthorizedRequestContext authorization
    ) {
        requireCredentialPermission(authorization);
        if (!authorization.tenantId().equals(tenantId)
                || !authorization.allowsMerchant(merchantId)) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (tenants.evaluate(TenantReference.from(tenantId)) != TenantActivityStatus.ALLOWED) {
            throw new CredentialAdministrationBlockedException(
                    "Credential activity requires an active Tenant");
        }
        if (merchants.evaluate(MerchantReference.from(tenantId, merchantId))
                != MerchantActivityStatus.ALLOWED) {
            throw new CredentialAdministrationBlockedException(
                    "Credential activity requires an active Merchant");
        }
    }

    private void requireCredentialPermission(AuthorizedRequestContext authorization) {
        if (!authorization.isHuman()) {
            throw new AuthorizationPermissionDeniedException("credential:manage");
        }
        if (!authorization.canManageCredentials()) {
            throw new AuthorizationPermissionDeniedException("credential:manage");
        }
    }

    private void requireConfirmed(boolean confirmation) {
        if (!confirmation) {
            throw new IllegalArgumentException(
                    "Credential action requires explicit confirmation");
        }
    }
}
