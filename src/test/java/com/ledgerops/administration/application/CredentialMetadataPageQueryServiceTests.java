package com.ledgerops.administration.application;

import com.ledgerops.administration.api.CredentialMetadataPageQuery;
import com.ledgerops.administration.api.CredentialMetadataPageResult;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialMetadataPageQueryServiceTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final UUID OPERATION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-08-09T10:00:00Z");

    @Test
    void returnsTheSafeFirstPageAndBindsTheNextCursorToItsFilters() {
        RecordingCredentials credentials = new RecordingCredentials();
        credentials.page = new ServiceCredentialPage(
                java.util.List.of(metadata()), true);
        CredentialMetadataPageQueryService service =
                new CredentialMetadataPageQueryService(credentials);

        CredentialMetadataPageResult result = service.findPage(new CredentialMetadataPageQuery(
                TENANT_ID,
                MERCHANT_ID,
                "active",
                1,
                null,
                authorization(ScopeMode.TENANT_WIDE, Set.of())
        ));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).credentialId()).isEqualTo(CREDENTIAL_ID);
        assertThat(result.items().get(0).status()).isEqualTo("ACTIVE");
        CredentialPageCursor cursor = CredentialPageCursorCodec.decode(result.nextCursor());
        assertThat(cursor.version()).isEqualTo(1);
        assertThat(cursor.tenantId()).isEqualTo(TENANT_ID);
        assertThat(cursor.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(cursor.status()).isEqualTo("ACTIVE");
        assertThat(cursor.createdAt()).isEqualTo(CREATED_AT);
        assertThat(cursor.credentialId()).isEqualTo(CREDENTIAL_ID);
        assertThat(credentials.query.limit()).isEqualTo(1);
        assertThat(credentials.query.beforeCreatedAt()).isNull();
        assertThat(credentials.query.beforeCredentialId()).isNull();
    }

    @Test
    void rejectsAWellFormedCursorForDifferentFilters() {
        String cursor = CredentialPageCursorCodec.encode(new CredentialPageCursor(
                1,
                TENANT_ID,
                MERCHANT_ID,
                "ACTIVE",
                CREATED_AT,
                CREDENTIAL_ID
        ));
        CredentialMetadataPageQueryService service =
                new CredentialMetadataPageQueryService(new RecordingCredentials());

        assertThatThrownBy(() -> service.findPage(new CredentialMetadataPageQuery(
                TENANT_ID,
                null,
                "ACTIVE",
                25,
                cursor,
                authorization(ScopeMode.TENANT_WIDE, Set.of())
        ))).isInstanceOf(InvalidCredentialCursorException.class);
    }

    @Test
    void rejectsMalformedCursors() {
        CredentialMetadataPageQueryService service =
                new CredentialMetadataPageQueryService(new RecordingCredentials());

        assertThatThrownBy(() -> service.findPage(new CredentialMetadataPageQuery(
                TENANT_ID,
                null,
                null,
                25,
                "not-a-cursor",
                authorization(ScopeMode.TENANT_WIDE, Set.of())
        ))).isInstanceOf(InvalidCredentialCursorException.class);
    }

    @Test
    void hidesARequestedMerchantOutsideTheAuthorizedScope() {
        CredentialMetadataPageQueryService service =
                new CredentialMetadataPageQueryService(new RecordingCredentials());

        assertThatThrownBy(() -> service.findPage(new CredentialMetadataPageQuery(
                TENANT_ID,
                MERCHANT_ID,
                null,
                25,
                null,
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
                CREATED_AT,
                CREATED_AT
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
                "credential-page-correlation"
        );
    }

    private static final class RecordingCredentials implements ServiceCredentialQueryPort {

        private ServiceCredentialPage page = new ServiceCredentialPage(
                java.util.List.of(), false);
        private ServiceCredentialPageQuery query;

        @Override
        public Optional<ServiceCredentialMetadata> find(UUID credentialId) {
            return Optional.empty();
        }

        @Override
        public ServiceCredentialPage findPage(ServiceCredentialPageQuery query) {
            this.query = query;
            return page;
        }

    }
}
