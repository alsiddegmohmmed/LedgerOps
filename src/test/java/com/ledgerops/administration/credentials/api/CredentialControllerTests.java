package com.ledgerops.administration.credentials.api;

import com.ledgerops.administration.api.CredentialAdministrationPort;
import com.ledgerops.administration.api.CredentialMetadataQuery;
import com.ledgerops.administration.api.CredentialMetadataQueryPort;
import com.ledgerops.administration.api.CredentialMetadataResult;
import com.ledgerops.administration.api.CredentialProvisioningCommand;
import com.ledgerops.administration.api.CredentialProvisioningResult;
import com.ledgerops.administration.api.CredentialRevocationCommand;
import com.ledgerops.administration.api.CredentialRevocationResult;
import com.ledgerops.administration.api.CredentialRotationCommand;
import com.ledgerops.administration.api.CredentialRotationResult;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.ServiceCredentialRevocationFailedException;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CredentialControllerTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final UUID REPLACEMENT_ID = UUID.randomUUID();
    private static final UUID OPERATION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private RecordingAdministration administration;
    private RecordingMetadataQuery metadata;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        administration = new RecordingAdministration();
        metadata = new RecordingMetadataQuery();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new CredentialController(administration),
                        new CredentialMetadataController(metadata))
                .setControllerAdvice(new CredentialProblemHandler())
                .build();
    }

    @Test
    void metadataReadReturnsSafeProjectionWithoutASecret() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{tenantId}/credentials/{credentialId}",
                                TENANT_ID, CREDENTIAL_ID)
                        .requestAttr(AuthorizedRequestContext.class.getName(), authorization())
                        .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), actor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialId").value(CREDENTIAL_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(metadata.query.credentialId())
                .isEqualTo(CREDENTIAL_ID);
    }

    @Test
    void createReturnsOneTimeSecretAndMapsTheTenantRoute() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/{tenantId}/credentials", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantId":"%s",
                                  "label":"Checkout",
                                  "confirmation":true,
                                  "reason":"Initial integration setup"
                                }
                                """.formatted(MERCHANT_ID))
                        .requestAttr(AuthorizedRequestContext.class.getName(), authorization())
                        .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), actor()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.credentialId").value(CREDENTIAL_ID.toString()))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.clientSecret").value("one-time-secret"));

        org.assertj.core.api.Assertions.assertThat(administration.provisioned.tenantId())
                .isEqualTo(TENANT_ID);
        org.assertj.core.api.Assertions.assertThat(administration.provisioned.merchantId())
                .isEqualTo(MERCHANT_ID);
    }

    @Test
    void rotateReturnsReplacementAndOneTimeSecret() throws Exception {
        mockMvc.perform(post(
                                "/api/v1/tenants/{tenantId}/credentials/{credentialId}/rotate",
                                TENANT_ID,
                                CREDENTIAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody("Rotate before planned rollout"))
                        .requestAttr(AuthorizedRequestContext.class.getName(), authorization())
                        .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), actor()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.previousCredentialId")
                        .value(CREDENTIAL_ID.toString()))
                .andExpect(jsonPath("$.credentialId").value(REPLACEMENT_ID.toString()))
                .andExpect(jsonPath("$.clientSecret").value("replacement-secret"));

        org.assertj.core.api.Assertions.assertThat(administration.rotated.credentialId())
                .isEqualTo(CREDENTIAL_ID);
    }

    @Test
    void revokeReturnsNoSecretAndMapsExternalCleanupFailureAsRetryable() throws Exception {
        administration.revocationFailure = true;

        mockMvc.perform(post(
                                "/api/v1/tenants/{tenantId}/credentials/{credentialId}/revoke",
                                TENANT_ID,
                                CREDENTIAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody("Emergency disable"))
                        .requestAttr(AuthorizedRequestContext.class.getName(), authorization())
                        .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), actor()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("CREDENTIAL_REVOCATION_CLEANUP_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(administration.revoked.credentialId())
                .isEqualTo(CREDENTIAL_ID);
    }

    private String actionBody(String reason) {
        return """
                {
                  "confirmation":true,
                  "reason":"%s"
                }
                """.formatted(reason);
    }

    private AuthorizedRequestContext authorization() {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                USER_ID,
                null,
                TENANT_ID,
                ScopeMode.TENANT_WIDE,
                Set.of(),
                Set.of(Permission.CREDENTIAL_MANAGE),
                "correlation-credential-http"
        );
    }

    private AuthenticatedPrincipal actor() {
        return new AuthenticatedPrincipal("HUMAN", "https://issuer.example", "operator");
    }

    private static final class RecordingAdministration implements CredentialAdministrationPort {

        private CredentialProvisioningCommand provisioned;
        private CredentialRotationCommand rotated;
        private CredentialRevocationCommand revoked;
        private boolean revocationFailure;

        @Override
        public CredentialProvisioningResult provision(CredentialProvisioningCommand command) {
            provisioned = command;
            return new CredentialProvisioningResult(
                    CREDENTIAL_ID,
                    OPERATION_ID,
                    TENANT_ID,
                    MERCHANT_ID,
                    "client-id",
                    "one-time-secret",
                    "ACTIVE"
            );
        }

        @Override
        public CredentialRotationResult rotate(CredentialRotationCommand command) {
            rotated = command;
            return new CredentialRotationResult(
                    CREDENTIAL_ID,
                    REPLACEMENT_ID,
                    OPERATION_ID,
                    TENANT_ID,
                    MERCHANT_ID,
                    "replacement-client-id",
                    "replacement-secret",
                    "ACTIVE"
            );
        }

        @Override
        public CredentialRevocationResult revoke(CredentialRevocationCommand command) {
            revoked = command;
            if (revocationFailure) {
                throw new ServiceCredentialRevocationFailedException(
                        CREDENTIAL_ID,
                        "KEYCLOAK_TIMEOUT",
                        "external cleanup failed",
                        new RuntimeException("not exposed"));
            }
            return new CredentialRevocationResult(
                    CREDENTIAL_ID,
                    OPERATION_ID,
                    TENANT_ID,
                    MERCHANT_ID,
                    "client-id",
                    "REVOKED"
            );
        }
    }

    private static final class RecordingMetadataQuery implements CredentialMetadataQueryPort {

        private CredentialMetadataQuery query;

        @Override
        public CredentialMetadataResult find(CredentialMetadataQuery query) {
            this.query = query;
            return new CredentialMetadataResult(
                    CREDENTIAL_ID,
                    TENANT_ID,
                    MERCHANT_ID,
                    "Checkout",
                    "client-id",
                    "ACTIVE",
                    OPERATION_ID,
                    null,
                    "CONSUMED",
                    Instant.parse("2026-08-09T10:00:00Z"),
                    Instant.parse("2026-08-09T10:00:00Z")
            );
        }
    }
}
