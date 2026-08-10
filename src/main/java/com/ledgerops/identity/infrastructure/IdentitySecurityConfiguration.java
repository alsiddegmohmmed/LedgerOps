package com.ledgerops.identity.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerops.identity.application.AuthorizedTenantContextPort;
import com.ledgerops.identity.application.RequestContextService;
import com.ledgerops.identity.api.SupportSessionPort;
import com.ledgerops.identity.domain.ApplicationUserRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(IdentityJwtProperties.class)
class IdentitySecurityConfiguration {

    @Bean
    RequestContextService requestContextService(
            ApplicationUserRepository applicationUsers,
            AuthorizedTenantContextPort tenantContexts
    ) {
        return new RequestContextService(applicationUsers, tenantContexts);
    }

    @Bean
    ObjectMapper identityProblemObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @Conditional(IdentityJwtConfigured.class)
    JwtPrincipalParser jwtPrincipalParser(IdentityJwtProperties properties) {
        JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        return new JwtPrincipalParser(decoder, properties.issuer(), properties.audience(), Clock.systemUTC());
    }

    @Bean
    @Conditional(IdentityJwtConfigured.class)
    RequestContextAuthenticationFilter requestContextAuthenticationFilter(
            JwtPrincipalParser parser,
            RequestContextService requestContextService,
            ObjectMapper objectMapper,
            SupportSessionPort supportSessions
    ) {
        return new RequestContextAuthenticationFilter(
                parser, requestContextService, objectMapper, supportSessions);
    }

    @Bean
    SecurityFilterChain identitySecurityFilterChain(
            HttpSecurity http,
            org.springframework.beans.factory.ObjectProvider<RequestContextAuthenticationFilter> filter
    ) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        RequestContextAuthenticationFilter authenticationFilter = filter.getIfAvailable();
        if (authenticationFilter != null) {
            http.addFilterAfter(authenticationFilter,
                    org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
        }
        return http.build();
    }

    static final class IdentityJwtConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return has(context, "ledgerops.identity.jwt.issuer")
                    && has(context, "ledgerops.identity.jwt.audience")
                    && has(context, "ledgerops.identity.jwt.jwk-set-uri");
        }

        private boolean has(ConditionContext context, String property) {
            String value = context.getEnvironment().getProperty(property);
            return value != null && !value.isBlank();
        }
    }
}
