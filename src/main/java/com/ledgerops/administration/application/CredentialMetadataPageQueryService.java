package com.ledgerops.administration.application;

import com.ledgerops.administration.api.CredentialMetadataPageQuery;
import com.ledgerops.administration.api.CredentialMetadataPageQueryPort;
import com.ledgerops.administration.api.CredentialMetadataPageResult;
import com.ledgerops.administration.api.CredentialMetadataResult;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.ServiceCredentialMetadata;
import com.ledgerops.identity.api.ServiceCredentialPage;
import com.ledgerops.identity.api.ServiceCredentialPageQuery;
import com.ledgerops.identity.api.ServiceCredentialQueryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service("administrationCredentialMetadataPageQueryService")
@ConditionalOnBean(ServiceCredentialQueryPort.class)
class CredentialMetadataPageQueryService implements CredentialMetadataPageQueryPort {

    private final ServiceCredentialQueryPort credentials;

    CredentialMetadataPageQueryService(ServiceCredentialQueryPort credentials) {
        this.credentials = Objects.requireNonNull(
                credentials, "Credential query port must not be null");
    }

    @Override
    public CredentialMetadataPageResult findPage(CredentialMetadataPageQuery query) {
        Objects.requireNonNull(query, "Credential metadata page query must not be null");
        requireListAccess(query);
        CredentialPageCursor cursor = query.cursor() == null
                ? null
                : CredentialPageCursorCodec.decode(query.cursor());
        validateCursor(cursor, query);

        ServiceCredentialPage page = credentials.findPage(new ServiceCredentialPageQuery(
                query.tenantId(),
                query.merchantId(),
                query.status(),
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.credentialId(),
                query.limit()
        ));
        List<CredentialMetadataResult> items = page.items().stream()
                .map(CredentialMetadataPageQueryService::metadata)
                .toList();
        String nextCursor = null;
        if (page.hasNext()) {
            CredentialMetadataResult last = items.get(items.size() - 1);
            nextCursor = CredentialPageCursorCodec.encode(new CredentialPageCursor(
                    1,
                    query.tenantId(),
                    query.merchantId(),
                    query.status(),
                    last.createdAt(),
                    last.credentialId()
            ));
        }
        return new CredentialMetadataPageResult(items, nextCursor);
    }

    private void requireListAccess(CredentialMetadataPageQuery query) {
        AuthorizedRequestContext authorization = query.authorization();
        if (!authorization.isHuman()) {
            throw new AuthorizationPermissionDeniedException("credential:manage");
        }
        if (!authorization.canManageCredentials()) {
            throw new AuthorizationPermissionDeniedException("credential:manage");
        }
        if (!authorization.tenantId().equals(query.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (query.merchantId() != null
                && !authorization.allowsMerchant(query.merchantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
    }

    private void validateCursor(
            CredentialPageCursor cursor,
            CredentialMetadataPageQuery query
    ) {
        if (cursor == null) {
            return;
        }
        if (cursor.version() != 1
                || !cursor.tenantId().equals(query.tenantId())
                || !Objects.equals(cursor.merchantId(), query.merchantId())
                || !Objects.equals(cursor.status(), query.status())) {
            throw new InvalidCredentialCursorException();
        }
    }

    private static CredentialMetadataResult metadata(ServiceCredentialMetadata metadata) {
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
}
