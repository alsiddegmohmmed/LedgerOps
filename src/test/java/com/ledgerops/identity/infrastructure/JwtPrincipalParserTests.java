package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.application.ValidatedPrincipal;
import com.ledgerops.identity.domain.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPrincipalParserTests {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
    private static final String ISSUER = "https://keycloak.example/realms/ledgerops";
    private static final String AUDIENCE = "ledgerops-core";

    private JwtPrincipalParser parser;
    private JwtEncoder encoder;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey signingKey = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .keyID("test-key")
                .build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                .build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        decoder.setJwtValidator(timestampValidator);
        parser = new JwtPrincipalParser(
                decoder,
                ISSUER,
                AUDIENCE,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void validatesAndParsesHumanPrincipal() {
        String token = token("human-subject", "alice", null, NOW.minusSeconds(30), NOW.plusSeconds(300));

        ValidatedPrincipal principal = parser.parse(token);

        assertThat(principal.principalType()).isEqualTo(PrincipalType.HUMAN);
        assertThat(principal.keycloakIdentity().issuer()).isEqualTo(ISSUER);
        assertThat(principal.keycloakIdentity().subject()).isEqualTo("human-subject");
        assertThat(principal.serviceClientId()).isNull();
    }

    @Test
    void parsesKeycloakServiceAccountPrincipalWithoutGrantingAuthority() {
        String token = token("service-subject", "service-account-ledger-client", "ledger-client",
                NOW.minusSeconds(30), NOW.plusSeconds(300));

        ValidatedPrincipal principal = parser.parse(token);

        assertThat(principal.principalType()).isEqualTo(PrincipalType.SERVICE);
        assertThat(principal.serviceClientId()).isEqualTo("ledger-client");
    }

    @Test
    void rejectsIssuerAudienceSignatureExpiryAndNotBeforeViolations() {
        assertInvalid("issuer", token("subject", "alice", null, NOW.minusSeconds(30), NOW.plusSeconds(300), "wrong-issuer", AUDIENCE));
        assertInvalid("audience", token("subject", "alice", null, NOW.minusSeconds(30), NOW.plusSeconds(300), ISSUER, "wrong-audience"));
        assertInvalid("expiry", token("subject", "alice", null, NOW.minusSeconds(30), NOW.minusSeconds(1)));
        assertInvalid("not-before", token("subject", "alice", null, NOW.plusSeconds(1), NOW.plusSeconds(300)));
        assertThatThrownBy(() -> parser.parse("not-a-jwt"))
                .isInstanceOf(InvalidJwtPrincipalException.class);
    }

    private void assertInvalid(String label, String token) {
        assertThatThrownBy(() -> parser.parse(token))
                .as(label)
                .isInstanceOf(InvalidJwtPrincipalException.class);
    }

    private String token(
            String subject,
            String username,
            String clientId,
            Instant notBefore,
            Instant expiresAt
    ) {
        return token(subject, username, clientId, notBefore, expiresAt, ISSUER, AUDIENCE);
    }

    private String token(
            String subject,
            String username,
            String clientId,
            Instant notBefore,
            Instant expiresAt,
            String issuer,
            String audience
    ) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .audience(List.of(audience))
                .issuedAt(NOW.minusSeconds(60))
                .notBefore(notBefore)
                .expiresAt(expiresAt)
                .claim("preferred_username", username);
        if (clientId != null) {
            claims.claim("azp", clientId);
        }
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                claims.build()
        )).getTokenValue();
    }
}
