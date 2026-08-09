package com.ledgerops.identity.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerops.identity.application.KeycloakCredentialProvisioner;
import com.ledgerops.identity.application.KeycloakCredentialProvisioningException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Keycloak Admin REST adapter for deterministic sandbox clients.
 *
 * <p>The adapter owns only the external identity boundary. It does not know
 * Core credential status and never writes a client secret to Core.</p>
 */
final class KeycloakCredentialProvisioningAdapter implements KeycloakCredentialProvisioner {

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private final KeycloakAdminProperties properties;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    KeycloakCredentialProvisioningAdapter(KeycloakAdminProperties properties) {
        this.properties = Objects.requireNonNull(properties, "Keycloak Admin properties must not be null");
        if (!properties.enabled()) {
            throw new IllegalArgumentException("Keycloak Admin adapter must be enabled");
        }
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    @Override
    public ProvisionedClient provision(ProvisioningRequest request) {
        Objects.requireNonNull(request, "Keycloak provisioning request must not be null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Keycloak Admin calls must execute outside a Core transaction");
        }

        try {
            String adminToken = obtainAdminToken();
            JsonNode client = findClient(adminToken, request.keycloakClientId());
            if (client == null) {
                createClient(adminToken, request);
                client = findClient(adminToken, request.keycloakClientId());
            } else {
                client = reconcileClient(adminToken, client, request);
            }
            if (client == null) {
                throw failure("KEYCLOAK_CLIENT_NOT_FOUND", "Provisioned Keycloak client could not be located");
            }
            return new ProvisionedClient(readClientSecret(adminToken, requiredText(client, "id")));
        } catch (KeycloakCredentialProvisioningException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("KEYCLOAK_INTERRUPTED", "Keycloak administration request was interrupted");
        } catch (IOException exception) {
            throw failure("KEYCLOAK_CONNECTION_FAILURE", "Keycloak administration request failed");
        }
    }

    private String obtainAdminToken() throws IOException, InterruptedException {
        String form = form(
                "grant_type", "client_credentials",
                "client_id", properties.clientId(),
                "client_secret", properties.clientSecret()
        );
        HttpResponse<String> response = send(
                "POST",
                tokenUri(),
                null,
                FORM_CONTENT_TYPE,
                form
        );
        if (response.statusCode() != 200) {
            throw failure("KEYCLOAK_ADMIN_AUTH_FAILED", "Keycloak Admin authentication failed");
        }
        JsonNode body = parse(response.body(), "Keycloak Admin token response");
        String token = text(body, "access_token");
        if (token == null) {
            throw failure("KEYCLOAK_ADMIN_AUTH_INVALID", "Keycloak Admin token response was invalid");
        }
        return token;
    }

    private JsonNode findClient(String token, String clientId) throws IOException, InterruptedException {
        HttpResponse<String> response = send(
                "GET",
                adminClientsUri("?clientId=" + encode(clientId)),
                token,
                null,
                null
        );
        if (response.statusCode() != 200) {
            throw httpFailure("KEYCLOAK_CLIENT_LOOKUP_FAILED", response.statusCode());
        }
        JsonNode clients = parse(response.body(), "Keycloak client lookup response");
        if (!clients.isArray()) {
            throw failure("KEYCLOAK_CLIENT_LOOKUP_INVALID", "Keycloak client lookup response was invalid");
        }
        for (JsonNode client : clients) {
            if (clientId.equals(text(client, "clientId"))) {
                return client;
            }
        }
        return null;
    }

    private void createClient(String token, ProvisioningRequest request)
            throws IOException, InterruptedException {
        ObjectNode body = desiredClient(request);
        HttpResponse<String> response = send(
                "POST",
                adminClientsUri(""),
                token,
                JSON_CONTENT_TYPE,
                body.toString()
        );
        if (response.statusCode() == 201 || response.statusCode() == 204) {
            return;
        }
        if (response.statusCode() == 409) {
            JsonNode raced = findClient(token, request.keycloakClientId());
            if (raced != null) {
                reconcileClient(token, raced, request);
                return;
            }
        }
        throw httpFailure("KEYCLOAK_CLIENT_CREATE_FAILED", response.statusCode());
    }

    private JsonNode reconcileClient(
            String token,
            JsonNode existing,
            ProvisioningRequest request
    ) throws IOException, InterruptedException {
        String internalId = requiredText(existing, "id");
        if (!request.keycloakClientId().equals(text(existing, "clientId"))) {
            throw failure("KEYCLOAK_CLIENT_CONFLICT", "Keycloak client identity does not match the operation");
        }
        if (booleanValue(existing, "publicClient") || booleanValue(existing, "bearerOnly")
                || !"openid-connect".equals(text(existing, "protocol"))) {
            throw failure("KEYCLOAK_CLIENT_CONFLICT", "Deterministic Keycloak client has incompatible security settings");
        }

        ObjectNode desired = existing.deepCopy();
        desired.put("clientId", request.keycloakClientId());
        desired.put("enabled", true);
        desired.put("publicClient", false);
        desired.put("bearerOnly", false);
        desired.put("serviceAccountsEnabled", true);
        desired.put("clientAuthenticatorType", "client-secret");
        desired.put("protocol", "openid-connect");
        desired.put("standardFlowEnabled", false);
        desired.put("implicitFlowEnabled", false);
        desired.put("directAccessGrantsEnabled", false);
        desired.put("name", request.label());

        if (!equivalentClientConfiguration(existing, desired)) {
            HttpResponse<String> response = send(
                    "PUT",
                    adminClientUri(internalId),
                    token,
                    JSON_CONTENT_TYPE,
                    desired.toString()
            );
            if (response.statusCode() != 204) {
                throw httpFailure("KEYCLOAK_CLIENT_RECONCILE_FAILED", response.statusCode());
            }
            return desired;
        }
        return existing;
    }

    private String readClientSecret(String token, String internalId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(
                "GET",
                adminClientSecretUri(internalId),
                token,
                null,
                null
        );
        if (response.statusCode() != 200) {
            throw httpFailure("KEYCLOAK_CLIENT_SECRET_READ_FAILED", response.statusCode());
        }
        JsonNode body = parse(response.body(), "Keycloak client secret response");
        String secret = text(body, "value");
        if (secret == null || secret.isBlank()) {
            throw failure("KEYCLOAK_CLIENT_SECRET_INVALID", "Keycloak returned an invalid client secret");
        }
        return secret;
    }

    private HttpResponse<String> send(
            String method,
            URI uri,
            String token,
            String contentType,
            String body
    ) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(properties.responseTimeout())
                .header("Accept", JSON_CONTENT_TYPE);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode desiredClient(ProvisioningRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("clientId", request.keycloakClientId());
        body.put("name", request.label());
        body.put("enabled", true);
        body.put("protocol", "openid-connect");
        body.put("publicClient", false);
        body.put("bearerOnly", false);
        body.put("serviceAccountsEnabled", true);
        body.put("clientAuthenticatorType", "client-secret");
        body.put("standardFlowEnabled", false);
        body.put("implicitFlowEnabled", false);
        body.put("directAccessGrantsEnabled", false);
        return body;
    }

    private boolean equivalentClientConfiguration(JsonNode existing, JsonNode desired) {
        return booleanValue(existing, "enabled") == booleanValue(desired, "enabled")
                && booleanValue(existing, "publicClient") == booleanValue(desired, "publicClient")
                && booleanValue(existing, "bearerOnly") == booleanValue(desired, "bearerOnly")
                && booleanValue(existing, "serviceAccountsEnabled")
                == booleanValue(desired, "serviceAccountsEnabled")
                && booleanValue(existing, "standardFlowEnabled")
                == booleanValue(desired, "standardFlowEnabled")
                && booleanValue(existing, "implicitFlowEnabled")
                == booleanValue(desired, "implicitFlowEnabled")
                && booleanValue(existing, "directAccessGrantsEnabled")
                == booleanValue(desired, "directAccessGrantsEnabled")
                && "client-secret".equals(text(existing, "clientAuthenticatorType"))
                && "client-secret".equals(text(desired, "clientAuthenticatorType"))
                && "openid-connect".equals(text(existing, "protocol"))
                && "openid-connect".equals(text(desired, "protocol"))
                && requestText(existing, "name").equals(requestText(desired, "name"));
    }

    private URI tokenUri() {
        return URI.create(properties.baseUrl() + "/realms/" + encodePath(properties.realm())
                + "/protocol/openid-connect/token");
    }

    private URI adminClientsUri(String suffix) {
        return URI.create(properties.baseUrl() + "/admin/realms/" + encodePath(properties.realm())
                + "/clients" + suffix);
    }

    private URI adminClientUri(String internalId) {
        return URI.create(properties.baseUrl() + "/admin/realms/" + encodePath(properties.realm())
                + "/clients/" + encodePath(internalId));
    }

    private URI adminClientSecretUri(String internalId) {
        return URI.create(properties.baseUrl() + "/admin/realms/" + encodePath(properties.realm())
                + "/clients/" + encodePath(internalId) + "/client-secret");
    }

    private JsonNode parse(String body, String responseDescription) {
        try {
            return json.readTree(body);
        } catch (JsonProcessingException exception) {
            throw failure("KEYCLOAK_RESPONSE_INVALID", responseDescription + " was not valid JSON");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw failure("KEYCLOAK_RESPONSE_INVALID", "Keycloak response did not contain " + field);
        }
        return value;
    }

    private static String requestText(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? "" : value;
    }

    private static boolean booleanValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.asBoolean(false);
    }

    private static String form(String... values) {
        List<String> encoded = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) {
            encoded.add(encode(values[i]) + "=" + encode(values[i + 1]));
        }
        return String.join("&", encoded);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    private static KeycloakCredentialProvisioningException httpFailure(String code, int status) {
        return failure(code, "Keycloak Admin API returned HTTP " + status);
    }

    private static KeycloakCredentialProvisioningException failure(String code, String detail) {
        return new KeycloakCredentialProvisioningException(code, detail);
    }
}
