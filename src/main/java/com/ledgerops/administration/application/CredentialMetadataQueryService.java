package com.ledgerops.administration.application;

import com.ledgerops.administration.api.CredentialMetadataQuery;
import com.ledgerops.administration.api.CredentialMetadataQueryPort;
import com.ledgerops.administration.api.CredentialMetadataResult;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.ServiceCredentialMetadata;
import com.ledgerops.identity.api.ServiceCredentialQueryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service("administrationCredentialMetadataQueryService")
@ConditionalOnBean(ServiceCredentialQueryPort.class)
class CredentialMetadataQueryService implements CredentialMetadataQueryPort {

    private final ServiceCredentialQueryPort credentials;

    CredentialMetadataQueryService(ServiceCredentialQueryPort credentials) {
        this.credentials = Objects.requireNonNull(
                credentials, "Credential query port must not be null");
    }

    @Override
    public CredentialMetadataResult find(CredentialMetadataQuery query) {
        Objects.requireNonNull(query, "Credential metadata query must not be null");
        ServiceCredentialMetadata metadata = credentials.find(query.credentialId())
                .orElseThrow(AuthorizationResourceNotFoundException::new);
        requireReadAccess(metadata, query.tenantId(), query.authorization());
        return new CredentialMetadataResult(
                metadata.credentialId(),
                metadata.tenantId(),
                metadata.merchantId(),
                metadata.label(),
                metadata.keycloakClientId(),
                metadata.status(),
                metadata.provisioningOperationId(),
                metadata.replacesCredentialId(),
                metadata.disclosureStatus(),
                metadata.createdAt(),
                metadata.updatedAt()
        );
    }

    private void requireReadAccess(
            ServiceCredentialMetadata metadata,
            java.util.UUID routeTenantId,
            AuthorizedRequestContext authorization
    ) {
        if (!authorization.isHuman()) {
            throw new AuthorizationPermissionDeniedException("credential:manage");
        }
        if (!authorization.canManageCredentials()) {
            throw new AuthorizationPermissionDeniedException("credential:manage");
        }
        if (!metadata.tenantId().equals(routeTenantId)
                || !authorization.tenantId().equals(metadata.tenantId())
                || !authorization.allowsMerchant(metadata.merchantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
    }
}
