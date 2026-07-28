package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.application.ValidatedPrincipal;
import com.ledgerops.identity.domain.KeycloakIdentity;
import com.ledgerops.identity.domain.PrincipalType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

public final class JwtPrincipalParser {

    private static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

    private final JwtDecoder decoder;
    private final String expectedIssuer;
    private final String expectedAudience;
    private final Clock clock;

    public JwtPrincipalParser(
            JwtDecoder decoder,
            String expectedIssuer,
            String expectedAudience,
            Clock clock
    ) {
        this.decoder = Objects.requireNonNull(decoder, "JWT decoder must not be null");
        this.expectedIssuer = requireConfiguration(expectedIssuer, "Expected issuer");
        this.expectedAudience = requireConfiguration(expectedAudience, "Expected audience");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public ValidatedPrincipal parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidJwtPrincipalException("JWT must not be blank");
        }

        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidJwtPrincipalException("JWT signature or structure is invalid", exception);
        }

        try {
            validateClaims(jwt);
            KeycloakIdentity identity = new KeycloakIdentity(
                    jwt.getIssuer().toString(),
                    jwt.getSubject()
            );
            String username = jwt.getClaimAsString("preferred_username");
            boolean service = username != null && username.startsWith(SERVICE_ACCOUNT_PREFIX);
            String clientId = service ? jwt.getClaimAsString("azp") : null;
            return new ValidatedPrincipal(
                    service ? PrincipalType.SERVICE : PrincipalType.HUMAN,
                    identity,
                    clientId
            );
        } catch (RuntimeException exception) {
            if (exception instanceof InvalidJwtPrincipalException invalid) {
                throw invalid;
            }
            throw new InvalidJwtPrincipalException("JWT identity claims are invalid", exception);
        }
    }

    private void validateClaims(Jwt jwt) {
        if (jwt.getIssuer() == null || !expectedIssuer.equals(jwt.getIssuer().toString())) {
            throw new InvalidJwtPrincipalException("JWT issuer is invalid");
        }

        Collection<String> audiences = jwt.getAudience();
        if (audiences == null || !audiences.contains(expectedAudience)) {
            throw new InvalidJwtPrincipalException("JWT audience is invalid");
        }

        Instant now = clock.instant();
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new InvalidJwtPrincipalException("JWT is expired");
        }

        Instant notBefore = jwt.getNotBefore();
        if (notBefore != null && notBefore.isAfter(now)) {
            throw new InvalidJwtPrincipalException("JWT is not valid yet");
        }

        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new InvalidJwtPrincipalException("JWT subject is invalid");
        }
    }

    private static String requireConfiguration(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
