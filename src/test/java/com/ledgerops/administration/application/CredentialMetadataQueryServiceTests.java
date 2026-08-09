package com.ledgerops.administration.application;

import com.ledgerops.administration.api.CredentialMetadataQuery;
import com.ledgerops.administration.api.CredentialMetadataResult;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.ServiceCredentialMetadata;
import com.ledgerops.identity.api.ServiceCredentialPage;
import com.ledgerops.identity.api.ServiceCredentialPageQuery;
import com.ledgerops.identity.api.ServiceCredentialQueryPort;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialMetadataQueryServiceTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final UUID OPERATION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");

    @Test
    void returnsOnlySafeMetadataForAQualifiedHuman() {
        CredentialMetadataQueryService service = new CredentialMetadataQueryService(
                new RecordingQuery(Optional.of(metadata())));

        CredentialMetadataResult result = service.find(new CredentialMetadataQuery(
                TENANT_ID,
                CREDENTIAL_ID,
                authorization(ScopeMode.TENANT_WIDE, Set.of())
        ));

        assertThat(result.credentialId()).isEqualTo(CREDENTIAL_ID);
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.keycloakClientId()).isEqualTo("client-id");
        assertThat(result.replacesCredentialId()).isNull();
    }

    @Test
    void hidesAResourceOutsideTheAuthorizedMerchantScope() {
        CredentialMetadataQueryService service = new CredentialMetadataQueryService(
                new RecordingQuery(Optional.of(metadata())));

        assertThatThrownBy(() -> service.find(new CredentialMetadataQuery(
                TENANT_ID,
                CREDENTIAL_ID,
                authorization(ScopeMode.MERCHANT_SET, Set.of(UUID.randomUUID()))
        ))).isInstanceOf(AuthorizationResourceNotFoundException.class);
    }

    private ServiceCredentialMetadata metadata() {
        return new ServiceCredentialMetadata(
                CREDENTIAL_ID,
                TENANT_ID,
                MERCHANT_ID,
                "Checkout",
                "client-id",
                "ACTIVE",
                USER_ID,
                OPERATION_ID,
                null,
                "CONSUMED",
                NOW,
                NOW
        );
    }

    private AuthorizedRequestContext authorization(
            ScopeMode scopeMode,
            Set<UUID> merchantIds
    ) {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                USER_ID,
                null,
                TENANT_ID,
                scopeMode,
                merchantIds,
                Set.of(Permission.CREDENTIAL_MANAGE),
                "credential-read-correlation"
        );
    }

    private record RecordingQuery(Optional<ServiceCredentialMetadata> result)
            implements ServiceCredentialQueryPort {
        @Override
        public Optional<ServiceCredentialMetadata> find(UUID credentialId) {
            return result;
        }

        @Override
        public ServiceCredentialPage findPage(ServiceCredentialPageQuery query) {
            return new ServiceCredentialPage(List.of(), false);
        }
    }
}
