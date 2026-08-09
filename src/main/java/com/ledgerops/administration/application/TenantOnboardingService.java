package com.ledgerops.administration.application;

import com.ledgerops.administration.api.TenantOnboardingCommand;
import com.ledgerops.administration.api.TenantOnboardingPort;
import com.ledgerops.administration.api.TenantOnboardingResult;
import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.IdentityOnboardingPort;
import com.ledgerops.identity.api.IdentityOnboardingResult;
import com.ledgerops.identity.api.InitialTenantAdminInvitationRequest;
import com.ledgerops.identity.api.PlatformAuthorityPort;
import com.ledgerops.merchant.api.MerchantOnboardingPort;
import com.ledgerops.merchant.api.MerchantOnboardingRequest;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.tenancy.api.TenantOnboardingRequest;
import com.ledgerops.tenancy.api.TenantReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantOnboardingService implements TenantOnboardingPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantOnboardingService.class);

    private final com.ledgerops.tenancy.api.TenantOnboardingPort tenants;
    private final MerchantOnboardingPort merchants;
    private final IdentityOnboardingPort identity;
    private final AuditAppendPort audit;
    private final PlatformAuthorityPort platformAuthority;

    public TenantOnboardingService(
            com.ledgerops.tenancy.api.TenantOnboardingPort tenants,
            MerchantOnboardingPort merchants,
            IdentityOnboardingPort identity,
            AuditAppendPort audit,
            PlatformAuthorityPort platformAuthority
    ) {
        this.tenants = tenants;
        this.merchants = merchants;
        this.identity = identity;
        this.audit = audit;
        this.platformAuthority = platformAuthority;
    }

    @Transactional
    @Override
    public TenantOnboardingResult onboard(TenantOnboardingCommand command) {
        platformAuthority.requirePlatformAdmin(new AuthenticatedPrincipal(
                "HUMAN", command.actorIssuer(), command.actorSubject()));

        TenantReference tenant = tenants.createPendingTenant(new TenantOnboardingRequest(
                command.tenantName(),
                command.defaultCurrency(),
                command.defaultLocale(),
                command.correlationId(),
                command.operationId()
        ));
        MerchantReference merchant = merchants.createInitialActiveMerchant(
                new MerchantOnboardingRequest(
                        tenant,
                        command.merchantName(),
                        command.correlationId(),
                        command.operationId()
                )
        );
        IdentityOnboardingResult identityResult = identity
                .createInitialTenantAdminInvitation(
                        new InitialTenantAdminInvitationRequest(
                                tenant.value(),
                                command.initialAdminEmail(),
                                command.invitationTokenHash(),
                                command.correlationId(),
                                command.operationId()
                        )
                );
        audit.appendTenantOnboarded(
                command.actorIssuer(),
                command.actorSubject(),
                tenant.value(),
                merchant.value(),
                identityResult.membershipId(),
                identityResult.invitationId(),
                command.correlationId().toString()
        );
        LOGGER.info(
                "Tenant onboarded tenantId={} merchantId={} membershipId={} invitationId={} correlationId={}",
                tenant.value(),
                merchant.value(),
                identityResult.membershipId(),
                identityResult.invitationId(),
                command.correlationId()
        );
        return new TenantOnboardingResult(
                tenant.value(),
                merchant.value(),
                identityResult.membershipId(),
                identityResult.invitationId()
        );
    }
}
