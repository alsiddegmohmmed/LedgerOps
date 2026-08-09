package com.ledgerops.administration.credentials.api;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialRelease03ContractTests {

    private static final Path CONTRACT = Path.of(
            "docs/api/release-0.3-credential-actions.yaml");

    @Test
    void publishesOnlyTheThreeImplementedCredentialActions() throws IOException {
        Map<String, Object> paths = map(loadContract().get("paths"));

        assertEquals(
                Set.of(
                        "/api/v1/tenants/{tenantId}/credentials",
                        "/api/v1/tenants/{tenantId}/credentials/{credentialId}/rotate",
                        "/api/v1/tenants/{tenantId}/credentials/{credentialId}/revoke"
                ),
                paths.keySet()
        );
        assertEquals(
                "provisionServiceCredential",
                operation(paths, "/api/v1/tenants/{tenantId}/credentials")
                        .get("operationId")
        );
        assertEquals(
                "rotateServiceCredential",
                operation(paths, "/api/v1/tenants/{tenantId}/credentials/{credentialId}/rotate")
                        .get("operationId")
        );
        assertEquals(
                "revokeServiceCredential",
                operation(paths, "/api/v1/tenants/{tenantId}/credentials/{credentialId}/revoke")
                        .get("operationId")
        );
    }

    @Test
    void keepsSecretDisclosureLimitedToCreateAndRotateResponses() throws IOException {
        Map<String, Object> schemas = map(map(loadContract().get("components")).get("schemas"));
        Map<String, Object> provisioning = map(schemas.get("CredentialProvisioningResponse"));
        Map<String, Object> rotation = map(schemas.get("CredentialRotationResponse"));
        Map<String, Object> revocation = map(schemas.get("CredentialRevocationResponse"));

        assertEquals(true, map(map(provisioning.get("properties")).get("clientSecret"))
                .get("readOnly"));
        assertEquals(true, map(map(rotation.get("properties")).get("clientSecret"))
                .get("readOnly"));
        assertFalse(map(revocation.get("properties")).containsKey("clientSecret"));
        assertTrue(list(map(schemas.get("CredentialActionRequest")).get("required"))
                .containsAll(List.of("confirmation", "reason")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadContract() throws IOException {
        try (InputStream input = Files.newInputStream(CONTRACT)) {
            return (Map<String, Object>) new Yaml(
                    new SafeConstructor(new LoaderOptions())).load(input);
        }
    }

    private Map<String, Object> operation(Map<String, Object> paths, String path) {
        return map(map(paths.get(path)).get("post"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Object value) {
        return (List<String>) value;
    }
}
