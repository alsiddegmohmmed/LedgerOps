package com.ledgerops.identity.application;

import com.ledgerops.identity.domain.PlatformAuthorityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
final class PlatformAdminBootstrap {

    private final PlatformAuthorityRepository authorities;
    private final boolean enabled;
    private final String issuer;
    private final String subject;

    PlatformAdminBootstrap(
            PlatformAuthorityRepository authorities,
            @Value("${ledgerops.identity.platform-admin.bootstrap-enabled:false}")
            boolean enabled,
            @Value("${ledgerops.identity.platform-admin.issuer:}")
            String issuer,
            @Value("${ledgerops.identity.platform-admin.subject:}")
            String subject
    ) {
        this.authorities = authorities;
        this.enabled = enabled;
        this.issuer = issuer;
        this.subject = subject;
    }

    @EventListener(ApplicationReadyEvent.class)
    void bootstrap() {
        if (!enabled) {
            return;
        }
        if (issuer.isBlank() || subject.isBlank()) {
            throw new IllegalStateException(
                    "Platform Admin bootstrap requires both issuer and subject");
        }
        authorities.ensurePlatformAdmin(issuer, subject, Instant.now());
    }
}
