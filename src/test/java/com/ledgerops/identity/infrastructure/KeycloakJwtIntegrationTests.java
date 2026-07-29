package com.ledgerops.identity.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerops.identity.application.ValidatedPrincipal;
import com.ledgerops.identity.domain.PrincipalType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class KeycloakJwtIntegrationTests {

    private static final String REALM = "ledgerops-slice1";
    private static final String HUMAN_CLIENT = "ledgerops-human";
    private static final String SERVICE_CLIENT = "ledgerops-service";
    private static final String SERVICE_SECRET = "slice1-service-secret";
    private static final long TOKEN_NOT_BEFORE = Instant.now().getEpochSecond();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.3.3")
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCommand("start-dev --http-port=8080")
            .waitingFor(Wait.forHttp("/").forStatusCode(200));

    @BeforeAll
    static void provisionRealm() throws Exception {
        String adminToken = token("master", "admin-cli", null, "password", "admin", "admin");
        postJson("/admin/realms", adminToken, """
                {"realm":"%s","enabled":true,"verifyEmail":false}
                """.formatted(REALM));
        postJson("/admin/realms/" + REALM + "/clients", adminToken, """
                {"clientId":"%s","enabled":true,"publicClient":true,
                 "directAccessGrantsEnabled":true,"standardFlowEnabled":true,
                 "protocol":"openid-connect","protocolMappers":[
                   {"name":"slice1-not-before","protocol":"openid-connect",
                    "protocolMapper":"oidc-hardcoded-claim-mapper",
                    "config":{"claim.name":"nbf","jsonType.label":"long",
                              "claim.value":"%d","access.token.claim":"true",
                              "id.token.claim":"false","userinfo.token.claim":"false"}}
                 ]}
                """.formatted(HUMAN_CLIENT, TOKEN_NOT_BEFORE));
        postJson("/admin/realms/" + REALM + "/clients", adminToken, """
                {"clientId":"%s","enabled":true,"publicClient":false,
                 "serviceAccountsEnabled":true,"clientAuthenticatorType":"client-secret",
                 "secret":"%s","protocol":"openid-connect"}
                """.formatted(SERVICE_CLIENT, SERVICE_SECRET));
        String serviceClientInternalId = JSON.readTree(getJson(
                "/admin/realms/" + REALM + "/clients?clientId=" + SERVICE_CLIENT, adminToken))
                .get(0).get("id").asText();
        String serviceAccountUserId = JSON.readTree(getJson(
                "/admin/realms/" + REALM + "/clients/" + serviceClientInternalId + "/service-account-user", adminToken))
                .get("id").asText();
        putJson("/admin/realms/" + REALM + "/users/" + serviceAccountUserId, adminToken,
                "{\"enabled\":true,\"requiredActions\":[]}");
        postJson("/admin/realms/" + REALM + "/users", adminToken, """
                {"username":"slice1-human","firstName":"Slice","lastName":"Human","enabled":true,"email":"slice1-human@example.test","emailVerified":true,"requiredActions":[],
                 "credentials":[{"type":"password","value":"slice1-password","temporary":false}]}
                """);
    }

    @Test
    void parsesKeycloakIssuedHumanAndServiceTokensThroughTheJwkEndpoint() throws Exception {
        String issuer = issuer();
        JwtPrincipalParser parser = parser(issuer, "account", Clock.systemUTC());

        ValidatedPrincipal human = parser.parse(token(REALM, HUMAN_CLIENT, null,
                "password", "slice1-human", "slice1-password"));
        ValidatedPrincipal service = parser.parse(token(REALM, SERVICE_CLIENT, SERVICE_SECRET,
                "client_credentials", null, null));

        assertThat(human.principalType()).isEqualTo(PrincipalType.HUMAN);
        assertThat(human.keycloakIdentity().issuer()).isEqualTo(issuer);
        assertThat(human.keycloakIdentity().subject()).isNotBlank();
        assertThat(service.principalType()).isEqualTo(PrincipalType.SERVICE);
        assertThat(service.serviceClientId()).isEqualTo(SERVICE_CLIENT);
        assertThat(service.keycloakIdentity().issuer()).isEqualTo(issuer);
        assertThat(service.keycloakIdentity().subject()).isNotBlank();
    }

    @Test
    void rejectsAKeycloakTokenWithAnInvalidSignature() throws Exception {
        String issuer = issuer();
        JwtPrincipalParser parser = parser(issuer, "account", Clock.systemUTC());
        String token = token(REALM, HUMAN_CLIENT, null, "password", "slice1-human", "slice1-password");
        String[] parts = token.split("\\.");
        String signature = parts[2];
        String tamperedSignature = (signature.charAt(0) == 'a' ? "b" : "a") + signature.substring(1);
        String tampered = parts[0] + "." + parts[1] + "." + tamperedSignature;

        assertThatCode(() -> parser.parse(tampered))
                .isInstanceOf(InvalidJwtPrincipalException.class);
    }

    @Test
    void rejectsKeycloakTokensWithInvalidIssuerAudienceExpiryAndNotBefore() throws Exception {
        String issuer = issuer();
        String humanToken = token(REALM, HUMAN_CLIENT, null,
                "password", "slice1-human", "slice1-password");
        NimbusJwtDecoder decoder = decoder(issuer);
        Jwt issuedToken = decoder.decode(humanToken);

        assertThatThrownBy(() -> parser(issuer + "/wrong", "account", Clock.systemUTC()).parse(humanToken))
                .isInstanceOf(InvalidJwtPrincipalException.class);
        assertThatThrownBy(() -> parser(issuer, "wrong-audience", Clock.systemUTC()).parse(humanToken))
                .isInstanceOf(InvalidJwtPrincipalException.class);

        Instant expiresAt = issuedToken.getExpiresAt();
        assertThat(expiresAt).isNotNull();
        assertThatThrownBy(() -> parser(issuer, "account",
                Clock.fixed(expiresAt.plusSeconds(1), ZoneOffset.UTC)).parse(humanToken))
                .isInstanceOf(InvalidJwtPrincipalException.class);

        Instant notBefore = issuedToken.getNotBefore();
        assertThat(notBefore).isNotNull();
        assertThatThrownBy(() -> parser(issuer, "account",
                Clock.fixed(notBefore.minusSeconds(1), ZoneOffset.UTC)).parse(humanToken))
                .isInstanceOf(InvalidJwtPrincipalException.class);
    }

    private static JwtPrincipalParser parser(String expectedIssuer, String expectedAudience, Clock clock) {
        return new JwtPrincipalParser(decoder(issuer()), expectedIssuer, expectedAudience, clock);
    }

    private static NimbusJwtDecoder decoder(String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(issuer + "/protocol/openid-connect/certs")
                .build();
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return decoder;
    }

    private static String issuer() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/" + REALM;
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
                .append("grant_type=").append(grantType)
                .append("&client_id=").append(clientId);
        form.append("&scope=openid");
        if (clientSecret != null) form.append("&client_secret=").append(clientSecret);
        if (username != null) form.append("&username=").append(username);
        if (password != null) form.append("&password=").append(password);
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/realms/" + realm + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s/%s %s: %s", realm, clientId, grantType, response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("access_token").asText();
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

    private static String getJson(String path, String bearer) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + bearer)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return response.body();
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
}
