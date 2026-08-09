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
    void publishesTheImplementedCredentialReadAndActionOperations() throws IOException {
        Map<String, Object> paths = map(loadContract().get("paths"));

        assertEquals(
                Set.of(
                        "/api/v1/tenants/{tenantId}/credentials/{credentialId}",
                        "/api/v1/tenants/{tenantId}/credentials",
                        "/api/v1/tenants/{tenantId}/credentials/{credentialId}/rotate",
                        "/api/v1/tenants/{tenantId}/credentials/{credentialId}/revoke"
                ),
                paths.keySet()
        );
        assertEquals(
                "getServiceCredentialMetadata",
                map(map(paths.get("/api/v1/tenants/{tenantId}/credentials/{credentialId}")).get("get"))
                        .get("operationId")
        );
        assertEquals(
                "provisionServiceCredential",
                operation(paths, "/api/v1/tenants/{tenantId}/credentials")
                        .get("operationId")
        );
        assertEquals(
                "listServiceCredentialMetadata",
                map(map(paths.get("/api/v1/tenants/{tenantId}/credentials")).get("get"))
                        .get("operationId")
        );
        Map<String, Object> list = map(
                map(paths.get("/api/v1/tenants/{tenantId}/credentials")).get("get"));
        List<Map<String, Object>> parameters = listOfMaps(list.get("parameters"));
        assertTrue(parameters.stream().anyMatch(parameter ->
                "limit".equals(parameter.get("name"))
                        && parameterNumber(parameter, "default") == 25
                        && parameterNumber(parameter, "minimum") == 1
                        && parameterNumber(parameter, "maximum") == 100));
        assertTrue(parameters.stream().anyMatch(parameter ->
                "cursor".equals(parameter.get("name"))));
        Map<String, Object> responseContent = map(
                map(map(list.get("responses")).get("200")).get("content"));
        Map<String, Object> responseSchema = map(
                map(responseContent.get("application/json")).get("schema"));
        assertEquals(
                "#/components/schemas/CredentialMetadataPageResponse",
                responseSchema.get("$ref")
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
        Map<String, Object> metadata = map(schemas.get("CredentialMetadataResponse"));
        Map<String, Object> provisioning = map(schemas.get("CredentialProvisioningResponse"));
        Map<String, Object> rotation = map(schemas.get("CredentialRotationResponse"));
        Map<String, Object> revocation = map(schemas.get("CredentialRevocationResponse"));
        Map<String, Object> page = map(schemas.get("CredentialMetadataPageResponse"));

        assertEquals(true, map(map(provisioning.get("properties")).get("clientSecret"))
                .get("readOnly"));
        assertEquals(true, map(map(rotation.get("properties")).get("clientSecret"))
                .get("readOnly"));
        assertFalse(map(revocation.get("properties")).containsKey("clientSecret"));
        assertFalse(map(metadata.get("properties")).containsKey("clientSecret"));
        Map<String, Object> pageItems = map(map(page.get("properties")).get("items"));
        assertFalse(pageItems.containsKey("clientSecret"));
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
    private List<Map<String, Object>> listOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private int parameterNumber(Map<String, Object> parameter, String name) {
        return ((Number) map(parameter.get("schema")).get(name)).intValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Object value) {
        return (List<String>) value;
    }
}
