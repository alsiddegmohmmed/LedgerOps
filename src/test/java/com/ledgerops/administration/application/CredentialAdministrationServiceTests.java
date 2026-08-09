package com.ledgerops.administration.application;

import com.ledgerops.administration.api.CredentialProvisioningCommand;
import com.ledgerops.administration.api.CredentialProvisioningResult;
import com.ledgerops.administration.api.CredentialRevocationCommand;
import com.ledgerops.administration.api.CredentialRotationCommand;
import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
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
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.merchant.api.MerchantActivityQuery;
import com.ledgerops.merchant.api.MerchantActivityStatus;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.tenancy.api.TenantActivityQuery;
import com.ledgerops.tenancy.api.TenantActivityStatus;
import com.ledgerops.tenancy.api.TenantReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialAdministrationServiceTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final UUID OPERATION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String CORRELATION_ID = UUID.randomUUID().toString();

    @Test
    void provisionsOnlyForAuthorizedActiveTenantAndMerchantAndAuditsWithoutSecret() {
        RecordingProvisioning provisioning = new RecordingProvisioning();
        RecordingAudit audit = new RecordingAudit();
        CredentialAdministrationService service = service(
                provisioning,
                new RecordingRevocation(),
                query(Optional.empty()),
                TenantActivityStatus.ALLOWED,
                MerchantActivityStatus.ALLOWED,
                audit
        );

        CredentialProvisioningResult result = service.provision(
                new CredentialProvisioningCommand(
                        TENANT_ID,
                        MERCHANT_ID,
                        "Sandbox checkout",
                        true,
                        "Initial integration setup",
                        authorization(ScopeMode.TENANT_WIDE, Set.of()),
                        actor()
                )
        );

        assertThat(result.credentialId()).isEqualTo(CREDENTIAL_ID);
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.clientSecret()).isEqualTo("secret-is-response-only");
        assertThat(provisioning.request.tenantId()).isEqualTo(TENANT_ID);
        assertThat(provisioning.request.createdByApplicationUserId()).isEqualTo(USER_ID);
        assertThat(audit.action).isEqualTo("provisioned");
        assertThat(audit.reason).isEqualTo("Initial integration setup");
        assertThat(audit.secret).isNull();
    }

    @Test
    void rejectsCredentialActionsWithoutPermissionOrExplicitConfirmation() {
        RecordingProvisioning provisioning = new RecordingProvisioning();
        CredentialAdministrationService service = service(
                provisioning,
                new RecordingRevocation(),
                query(Optional.empty()),
                TenantActivityStatus.ALLOWED,
                MerchantActivityStatus.ALLOWED,
                new RecordingAudit()
        );

        assertThatThrownBy(() -> service.provision(
                new CredentialProvisioningCommand(
                        TENANT_ID,
                        MERCHANT_ID,
                        "Sandbox checkout",
                        false,
                        "Missing confirmation",
                        authorizationWithoutPermission(),
                        actor()
                )
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.provision(
                new CredentialProvisioningCommand(
                        TENANT_ID,
                        MERCHANT_ID,
                        "Sandbox checkout",
                        true,
                        "Permission test",
                        authorizationWithoutPermission(),
                        actor()
                )
        )).isInstanceOf(AuthorizationPermissionDeniedException.class);

        assertThat(provisioning.request).isNull();
    }

    @Test
    void rejectsSensitiveReasonBeforeAnyCredentialEffect() {
        assertThatThrownBy(() -> new CredentialProvisioningCommand(
                TENANT_ID,
                MERCHANT_ID,
                "Sandbox checkout",
                true,
                "Store the client_secret here",
                authorization(ScopeMode.TENANT_WIDE, Set.of()),
                actor()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prohibited sensitive content");
    }

    @Test
    void doesNotRevealCredentialExistenceOutsideTenantOrMerchantScope() {
        ServiceCredentialMetadata metadata = metadata(TENANT_ID, MERCHANT_ID);
        CredentialAdministrationService service = service(
                new RecordingProvisioning(),
                new RecordingRevocation(),
                query(Optional.of(metadata)),
                TenantActivityStatus.ALLOWED,
                MerchantActivityStatus.ALLOWED,
                new RecordingAudit()
        );

        assertThatThrownBy(() -> service.rotate(
                new CredentialRotationCommand(
                        UUID.randomUUID(),
                        CREDENTIAL_ID,
                        true,
                        "Rotate outside route Tenant",
                        authorization(ScopeMode.TENANT_WIDE, Set.of()),
                        actor()
                )
        )).isInstanceOf(AuthorizationResourceNotFoundException.class);

        assertThatThrownBy(() -> service.revoke(
                new CredentialRevocationCommand(
                        TENANT_ID,
                        CREDENTIAL_ID,
                        true,
                        "Revoke outside Merchant scope",
                        authorization(ScopeMode.MERCHANT_SET, Set.of(UUID.randomUUID())),
                        actor()
                )
        )).isInstanceOf(AuthorizationResourceNotFoundException.class);
    }

    @Test
    void permitsFailSafeRevocationWhenTenantIsInactive() {
        RecordingRevocation revocation = new RecordingRevocation();
        CredentialAdministrationService service = service(
                new RecordingProvisioning(),
                revocation,
                query(Optional.of(metadata(TENANT_ID, MERCHANT_ID))),
                TenantActivityStatus.INACTIVE,
                MerchantActivityStatus.INACTIVE,
                new RecordingAudit()
        );

        service.revoke(new CredentialRevocationCommand(
                TENANT_ID,
                CREDENTIAL_ID,
                true,
                "Emergency disable during suspension",
                authorization(ScopeMode.TENANT_WIDE, Set.of()),
                actor()
        ));

        assertThat(revocation.credentialId).isEqualTo(CREDENTIAL_ID);
    }

    @Test
    void blocksProvisioningWhenTenantOrMerchantIsInactive() {
        CredentialAdministrationService service = service(
                new RecordingProvisioning(),
                new RecordingRevocation(),
                query(Optional.empty()),
                TenantActivityStatus.INACTIVE,
                MerchantActivityStatus.ALLOWED,
                new RecordingAudit()
        );

        assertThatThrownBy(() -> service.provision(new CredentialProvisioningCommand(
                TENANT_ID,
                MERCHANT_ID,
                "Sandbox checkout",
                true,
                "Inactive Tenant test",
                authorization(ScopeMode.TENANT_WIDE, Set.of()),
                actor()
        ))).isInstanceOf(CredentialAdministrationBlockedException.class);
    }

    private CredentialAdministrationService service(
            RecordingProvisioning provisioning,
            RecordingRevocation revocation,
            RecordingQuery query,
            TenantActivityStatus tenantStatus,
            MerchantActivityStatus merchantStatus,
            RecordingAudit audit
    ) {
        return new CredentialAdministrationService(
                provisioning,
                revocation,
                query,
                new RecordingTenantActivity(tenantStatus),
                new RecordingMerchantActivity(merchantStatus),
                audit
        );
    }

    private AuthorizedRequestContext authorization(ScopeMode scopeMode, Set<UUID> merchantIds) {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                USER_ID,
                null,
                TENANT_ID,
                scopeMode,
                merchantIds,
                Set.of(Permission.CREDENTIAL_MANAGE),
                CORRELATION_ID
        );
    }

    private AuthorizedRequestContext authorizationWithoutPermission() {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                USER_ID,
                null,
                TENANT_ID,
                ScopeMode.TENANT_WIDE,
                Set.of(),
                Set.of(),
                CORRELATION_ID
        );
    }

    private AuthenticatedPrincipal actor() {
        return new AuthenticatedPrincipal("HUMAN", "https://issuer.example", "admin");
    }

    private ServiceCredentialMetadata metadata(UUID tenantId, UUID merchantId) {
        Instant now = Instant.parse("2026-08-09T00:00:00Z");
        return new ServiceCredentialMetadata(
                CREDENTIAL_ID,
                tenantId,
                merchantId,
                "Sandbox checkout",
                "ledgerops-sandbox-credential-" + CREDENTIAL_ID,
                "ACTIVE",
                USER_ID,
                OPERATION_ID,
                null,
                "CONSUMED",
                now,
                now
        );
    }

    private RecordingQuery query(Optional<ServiceCredentialMetadata> result) {
        return new RecordingQuery(result);
    }

    private static class RecordingProvisioning implements ServiceCredentialProvisioningPort {
        private final ServiceCredentialProvisioningResult result =
                new ServiceCredentialProvisioningResult(
                        CREDENTIAL_ID,
                        OPERATION_ID,
                        TENANT_ID,
                        MERCHANT_ID,
                        "ledgerops-sandbox-credential-" + CREDENTIAL_ID,
                        "secret-is-response-only"
                );
        private ServiceCredentialProvisioningRequest request;

        @Override
        public ServiceCredentialProvisioningResult provision(
                ServiceCredentialProvisioningRequest request
        ) {
            this.request = request;
            return result;
        }

        @Override
        public ServiceCredentialProvisioningResult retry(UUID operationId) {
            return result;
        }

        @Override
        public ServiceCredentialProvisioningResult rotate(UUID credentialId) {
            return result;
        }

        @Override
        public void retryRotationCleanup(UUID replacementCredentialId) {
        }
    }

    private static class RecordingRevocation implements ServiceCredentialRevocationPort {
        private final ServiceCredentialRevocationResult result =
                new ServiceCredentialRevocationResult(
                        CREDENTIAL_ID,
                        OPERATION_ID,
                        TENANT_ID,
                        MERCHANT_ID,
                        "ledgerops-sandbox-credential-" + CREDENTIAL_ID
                );
        private UUID credentialId;

        @Override
        public ServiceCredentialRevocationResult revoke(UUID credentialId) {
            this.credentialId = credentialId;
            return result;
        }
    }

    private record RecordingQuery(Optional<ServiceCredentialMetadata> result)
            implements ServiceCredentialQueryPort {
        @Override
        public Optional<ServiceCredentialMetadata> find(UUID credentialId) {
            return result;
        }
    }

    private record RecordingTenantActivity(TenantActivityStatus status)
            implements TenantActivityQuery {
        @Override
        public TenantActivityStatus evaluate(TenantReference tenantReference) {
            return status;
        }

        @Override
        public TenantActivityStatus evaluateForUpdate(TenantReference tenantReference) {
            return status;
        }
    }

    private record RecordingMerchantActivity(MerchantActivityStatus status)
            implements MerchantActivityQuery {
        @Override
        public MerchantActivityStatus evaluate(MerchantReference merchantReference) {
            return status;
        }
    }

    private static class RecordingAudit implements AuditAppendPort {
        private String action;
        private String reason;
        private String secret;

        @Override
        public void appendPaymentCreated(String actorIssuer, String actorSubject,
                                         String principalType, UUID tenantId,
                                         UUID paymentId, String correlationId) {
        }

        @Override
        public void appendIdentityMembershipAccepted(String actorIssuer, String actorSubject,
                                                      UUID tenantId, UUID membershipId,
                                                      UUID applicationUserId,
                                                      String correlationId) {
        }

        @Override
        public void appendTenantOnboarded(String actorIssuer, String actorSubject,
                                          UUID tenantId, UUID merchantId,
                                          UUID membershipId, UUID invitationId,
                                          String correlationId) {
        }

        @Override
        public void appendMerchantLifecycleChanged(String actorIssuer, String actorSubject,
                                                   UUID tenantId, UUID merchantId,
                                                   String previousStatus, String status,
                                                   String correlationId) {
        }

        @Override
        public void appendTenantLifecycleChanged(String actorIssuer, String actorSubject,
                                                 UUID tenantId, String previousStatus,
                                                 String status, String correlationId) {
        }

        @Override
        public void appendTenantConfigurationChanged(String actorIssuer, String actorSubject,
                                                     UUID tenantId, long version,
                                                     String correlationId) {
        }

        @Override
        public void appendOperationalContactChanged(String actorIssuer, String actorSubject,
                                                    UUID tenantId, UUID contactId,
                                                    long version, String correlationId) {
        }

        @Override
        public void appendCredentialProvisioned(String actorIssuer, String actorSubject,
                                                UUID tenantId, UUID merchantId,
                                                UUID credentialId, UUID operationId,
                                                String reason, String correlationId) {
            action = "provisioned";
            this.reason = reason;
        }

        @Override
        public void appendCredentialRotated(String actorIssuer, String actorSubject,
                                            UUID tenantId, UUID merchantId,
                                            UUID previousCredentialId,
                                            UUID replacementCredentialId,
                                            String reason, String correlationId) {
            action = "rotated";
            this.reason = reason;
        }

        @Override
        public void appendCredentialRevoked(String actorIssuer, String actorSubject,
                                            UUID tenantId, UUID merchantId,
                                            UUID credentialId, String reason,
                                            String correlationId) {
            action = "revoked";
            this.reason = reason;
        }
    }
}
