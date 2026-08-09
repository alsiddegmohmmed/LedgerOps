package com.ledgerops.identity.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerops.identity.application.KeycloakCredentialDisabler;
import com.ledgerops.identity.application.KeycloakCredentialProvisioner;
import com.ledgerops.identity.application.KeycloakCredentialProvisioningException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class KeycloakCredentialProvisioningAdapterIntegrationTests {

    private static final String REALM = "ledgerops-slice2c";
    private static final String ADMIN_CLIENT = "slice2c-admin";
    private static final String ADMIN_SECRET = "slice2c-admin-secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
            "quay.io/keycloak/keycloak:26.3.3")
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCommand("start-dev --http-port=8080")
            .waitingFor(Wait.forHttp("/").forStatusCode(200));

    private static String masterToken;

    @BeforeAll
    static void provisionAdminRealm() throws Exception {
        masterToken = token("master", "admin-cli", null, "password", "admin", "admin");
        postJson("/admin/realms", masterToken, """
                {"realm":"%s","enabled":true,"verifyEmail":false}
                """.formatted(REALM));
        postJson("/admin/realms/" + REALM + "/clients", masterToken, """
                {"clientId":"%s","enabled":true,"publicClient":false,
                 "serviceAccountsEnabled":true,"clientAuthenticatorType":"client-secret",
                 "secret":"%s","protocol":"openid-connect",
                 "standardFlowEnabled":false,"directAccessGrantsEnabled":false}
                """.formatted(ADMIN_CLIENT, ADMIN_SECRET));

        String adminInternalId = firstClient(REALM, ADMIN_CLIENT, masterToken).get("id").asText();
        String serviceAccountUserId = JSON.readTree(getJson(
                "/admin/realms/" + REALM + "/clients/" + adminInternalId + "/service-account-user",
                masterToken)).get("id").asText();
        JsonNode realmManagement = firstClient(REALM, "realm-management", masterToken);
        JsonNode manageClientsRole = JSON.readTree(getJson(
                "/admin/realms/" + REALM + "/clients/" + realmManagement.get("id").asText()
                        + "/roles/manage-clients",
                masterToken));
        postJson(
                "/admin/realms/" + REALM + "/users/" + serviceAccountUserId
                        + "/role-mappings/clients/" + realmManagement.get("id").asText(),
                masterToken,
                JSON.writeValueAsString(JSON.createArrayNode().add(manageClientsRole))
        );
    }

    @Test
    void createsAndReconcilesOneDeterministicServiceClient() throws Exception {
        KeycloakCredentialProvisioningAdapter adapter = adapter();
        String clientId = "ledgerops-slice2c-" + UUID.randomUUID();

        KeycloakCredentialProvisioner.ProvisionedClient first = adapter.provision(
                request(clientId, "First label"));
        assertThat(first.clientSecret()).isNotBlank();

        JsonNode created = firstClient(REALM, clientId, masterToken);
        assertThat(created.get("publicClient").asBoolean()).isFalse();
        assertThat(created.get("bearerOnly").asBoolean()).isFalse();
        assertThat(created.get("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(created.get("clientAuthenticatorType").asText()).isEqualTo("client-secret");

        ObjectNode stale = (ObjectNode) created.deepCopy();
        stale.put("enabled", false);
        stale.put("serviceAccountsEnabled", false);
        stale.put("name", "stale label");
        putJson("/admin/realms/" + REALM + "/clients/" + created.get("id").asText(), masterToken,
                stale.toString());

        KeycloakCredentialProvisioner.ProvisionedClient second = adapter.provision(
                request(clientId, "Reconciled label"));
        assertThat(second.clientSecret()).isEqualTo(first.clientSecret());

        JsonNode reconciled = firstClient(REALM, clientId, masterToken);
        assertThat(reconciled.get("enabled").asBoolean()).isTrue();
        assertThat(reconciled.get("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(reconciled.get("name").asText()).isEqualTo("Reconciled label");
        JsonNode matchingClients = JSON.readTree(getJson(
                "/admin/realms/" + REALM + "/clients?clientId=" + encode(clientId),
                masterToken));
        assertThat(matchingClients.isArray()).isTrue();
        assertThat(matchingClients.size()).isEqualTo(1);
    }

    @Test
    void rejectsAnExistingPublicClientWithTheSameDeterministicIdentity() throws Exception {
        KeycloakCredentialProvisioningAdapter adapter = adapter();
        String clientId = "ledgerops-slice2c-conflict-" + UUID.randomUUID();
        postJson("/admin/realms/" + REALM + "/clients", masterToken, """
                {"clientId":"%s","enabled":true,"publicClient":true,
                 "protocol":"openid-connect","standardFlowEnabled":true}
                """.formatted(clientId));

        assertThatThrownBy(() -> adapter.provision(request(clientId, "Conflict")))
                .isInstanceOfSatisfying(KeycloakCredentialProvisioningException.class,
                        exception -> assertThat(exception.code()).isEqualTo("KEYCLOAK_CLIENT_CONFLICT"));
    }

    @Test
    void translatesAnUnavailableKeycloakAdminEndpointToATypedFailure() {
        KeycloakCredentialProvisioningAdapter adapter = new KeycloakCredentialProvisioningAdapter(
                new KeycloakAdminProperties(
                        true,
                        "http://127.0.0.1:1",
                        REALM,
                        ADMIN_CLIENT,
                        ADMIN_SECRET,
                        Duration.ofMillis(100),
                        Duration.ofMillis(250)
                )
        );

        assertThatThrownBy(() -> adapter.provision(request("unreachable-client", "Unavailable")))
                .isInstanceOfSatisfying(KeycloakCredentialProvisioningException.class,
                        exception -> assertThat(exception.code()).isEqualTo("KEYCLOAK_CONNECTION_FAILURE"));
    }

    @Test
    void refusesToCallKeycloakInsideAnActiveCoreTransaction() {
        KeycloakCredentialProvisioningAdapter adapter = adapter();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> adapter.provision(request("transaction-client", "Transaction")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Keycloak Admin calls must execute outside a Core transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void disablesAnExistingClientIdempotently() throws Exception {
        KeycloakCredentialProvisioningAdapter adapter = adapter();
        String clientId = "ledgerops-slice2c-revocation-" + UUID.randomUUID();
        adapter.provision(request(clientId, "Revocation"));

        KeycloakCredentialDisabler.DisableRequest disable =
                new KeycloakCredentialDisabler.DisableRequest(UUID.randomUUID(), clientId);
        adapter.disable(disable);

        JsonNode disabled = firstClient(REALM, clientId, masterToken);
        assertThat(disabled.get("enabled").asBoolean()).isFalse();

        adapter.disable(disable);
        JsonNode stillDisabled = firstClient(REALM, clientId, masterToken);
        assertThat(stillDisabled.get("enabled").asBoolean()).isFalse();
    }

    private static KeycloakCredentialProvisioningAdapter adapter() {
        return new KeycloakCredentialProvisioningAdapter(new KeycloakAdminProperties(
                true,
                baseUrl(),
                REALM,
                ADMIN_CLIENT,
                ADMIN_SECRET,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
        ));
    }

    private static KeycloakCredentialProvisioner.ProvisioningRequest request(
            String clientId,
            String label
    ) {
        return new KeycloakCredentialProvisioner.ProvisioningRequest(UUID.randomUUID(), clientId, label);
    }

    private static JsonNode firstClient(String realm, String clientId, String bearer) throws Exception {
        JsonNode clients = JSON.readTree(getJson(
                "/admin/realms/" + realm + "/clients?clientId=" + encode(clientId),
                bearer));
        assertThat(clients.isArray()).as("Keycloak client %s", clientId).isTrue();
        assertThat(clients.size()).as("Keycloak client %s", clientId).isGreaterThan(0);
        return clients.get(0);
    }

    private static String token(
            String realm,
            String clientId,
            String clientSecret,
            String grantType,
            String username,
            String password
    ) throws Exception {
        StringBuilder form = new StringBuilder()
                .append("grant_type=").append(encode(grantType))
                .append("&client_id=").append(encode(clientId));
        if (clientSecret != null) form.append("&client_secret=").append(encode(clientSecret));
        if (username != null) form.append("&username=").append(encode(username));
        if (password != null) form.append("&password=").append(encode(password));
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/realms/" + realm + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("access_token").asText();
    }

    private static String getJson(String path, String bearer) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + bearer)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return response.body();
    }

    private static void postJson(String path, String bearer, String body) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
    }

    private static void putJson(String path, String bearer, String body) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
    }

    private static String baseUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
