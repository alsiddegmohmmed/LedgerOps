package com.ledgerops.risk.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.risk.api.RiskConfigurationSnapshot;
import com.ledgerops.risk.api.RiskConfigurationStore;
import com.ledgerops.risk.api.RiskRuleConfiguration;
import com.ledgerops.risk.domain.PaymentAmountThresholdRule;
import com.ledgerops.risk.domain.RiskProfile;
import com.ledgerops.risk.domain.RiskProfileId;
import com.ledgerops.risk.domain.RiskRuleId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RiskConfigurationService {

    private final RiskConfigurationStore store;
    private final AuditAppendPort audit;
    private final Clock clock;

    public RiskConfigurationService(
            RiskConfigurationStore store,
            AuditAppendPort audit,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "Risk configuration store must not be null");
        this.audit = Objects.requireNonNull(audit, "Audit append port must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Transactional(readOnly = true)
    public RiskConfigurationSnapshot current(
            UUID tenantId,
            AuthorizedRequestContext authorization
    ) {
        requireTenant(tenantId, authorization);
        if (!authorization.canReadRiskConfiguration()) {
            throw new AuthorizationPermissionDeniedException("risk:read");
        }
        return store.findActiveProfile(tenantId)
                .map(this::snapshot)
                .orElseThrow(() -> new RiskConfigurationNotFoundException(tenantId));
    }

    @Transactional(readOnly = true)
    public List<RiskConfigurationSnapshot> history(
            UUID tenantId,
            AuthorizedRequestContext authorization
    ) {
        requireTenant(tenantId, authorization);
        if (!authorization.canReadRiskConfiguration()) {
            throw new AuthorizationPermissionDeniedException("risk:read");
        }
        return store.findProfileHistory(tenantId).stream().map(this::snapshot).toList();
    }

    @Transactional
    public RiskConfigurationSnapshot update(
            UUID tenantId,
            int reviewThreshold,
            int rejectThreshold,
            List<RiskRuleConfiguration> rules,
            Long expectedVersion,
            boolean confirmation,
            String reason,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        requireTenant(tenantId, authorization);
        if (!authorization.canManageRiskConfiguration()) {
            throw new AuthorizationPermissionDeniedException("risk:configuration-manage");
        }
        if (!authorization.isTenantWide()) {
            throw new AuthorizationPermissionDeniedException("risk:configuration-manage (Tenant-wide)");
        }
        if (!confirmation) {
            throw new IllegalArgumentException("Confirmation is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reason is required");
        }
        Objects.requireNonNull(rules, "Risk rules must not be null");

        RiskProfile previous = store.findActiveProfile(tenantId).orElse(null);
        long nextVersion = previous == null ? 1 : previous.version() + 1;
        RiskProfileId profileId = RiskProfileId.newId();
        List<PaymentAmountThresholdRule> domainRules = rules.stream()
                .map(rule -> new PaymentAmountThresholdRule(
                        RiskRuleId.newId(), profileId,
                        currency(rule.currency()), rule.amountThreshold(),
                        rule.scoreContribution(), rule.enabled()))
                .toList();
        RiskProfile profile = new RiskProfile(
                profileId, tenantId, nextVersion, reviewThreshold, rejectThreshold,
                true, clock.instant(), domainRules);
        RiskProfile stored = store.appendActiveProfile(profile, expectedVersion);

        audit.appendAction(
                actor == null ? "system" : actor.issuer(),
                actor == null ? "risk-configuration" : actor.subject(),
                actor == null ? "SYSTEM" : actor.principalType(),
                tenantId,
                "risk.configuration.changed",
                "risk-profile",
                stored.profileId().value().toString(),
                reason,
                beforeAfter(previous, stored),
                authorization.correlationId()
        );
        return snapshot(stored);
    }

    private void requireTenant(UUID tenantId, AuthorizedRequestContext authorization) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        if (!tenantId.equals(authorization.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
    }

    private Currency currency(String value) {
        try {
            return Currency.getInstance(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Risk rule currency must be an ISO 4217 code", exception);
        }
    }

    private RiskConfigurationSnapshot snapshot(RiskProfile profile) {
        return new RiskConfigurationSnapshot(
                profile.tenantId(), profile.profileId().value(), profile.version(),
                profile.reviewThreshold(), profile.rejectThreshold(), profile.active(),
                profile.createdAt(), profile.rules().stream()
                        .map(rule -> new RiskRuleConfiguration(
                                rule.currency().getCurrencyCode(), rule.amountThreshold(),
                                rule.scoreContribution(), rule.enabled()))
                        .toList());
    }

    private String beforeAfter(RiskProfile previous, RiskProfile current) {
        String before = previous == null ? "null" : "{\"profileId\":\""
                + previous.profileId().value() + "\",\"version\":" + previous.version() + "}";
        return "{\"before\":" + before + ",\"after\":{\"profileId\":\""
                + current.profileId().value() + "\",\"version\":" + current.version() + "}}";
    }
}
