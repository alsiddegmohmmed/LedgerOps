package com.ledgerops.administration.application;

import com.ledgerops.administration.api.MerchantLifecycleCommand;
import com.ledgerops.administration.api.MerchantLifecycleResult;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.merchant.api.MerchantLifecycleRequest;
import com.ledgerops.merchant.api.MerchantReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Function;

@Service("administrationMerchantLifecycleService")
class MerchantLifecycleService implements com.ledgerops.administration.api.MerchantLifecyclePort {

    private final com.ledgerops.merchant.api.MerchantLifecyclePort merchantLifecycle;

    MerchantLifecycleService(
            @Qualifier("merchantLifecycleService")
            com.ledgerops.merchant.api.MerchantLifecyclePort merchantLifecycle
    ) {
        this.merchantLifecycle = merchantLifecycle;
    }

    @Override
    @Transactional
    public MerchantLifecycleResult suspend(MerchantLifecycleCommand command) {
        return transition(command, merchantLifecycle::suspend, "SUSPENDED");
    }

    @Override
    @Transactional
    public MerchantLifecycleResult activate(MerchantLifecycleCommand command) {
        return transition(command, merchantLifecycle::activate, "ACTIVE");
    }

    private MerchantLifecycleResult transition(
            MerchantLifecycleCommand command,
            Function<MerchantLifecycleRequest, MerchantReference> transition,
            String resultingStatus
    ) {
        requireAuthorized(command);
        if (!command.confirmation()) {
            throw new IllegalArgumentException(
                    "Merchant lifecycle action requires explicit confirmation");
        }
        MerchantReference merchant = transition.apply(new MerchantLifecycleRequest(
                MerchantReference.from(command.tenantId(), command.merchantId()),
                command.actor().issuer(),
                command.actor().subject(),
                command.correlationId(),
                command.operationId(),
                command.reason()
        ));
        return new MerchantLifecycleResult(
                merchant.tenantId(), merchant.value(), resultingStatus);
    }

    private void requireAuthorized(MerchantLifecycleCommand command) {
        var context = command.authorization();
        if (!context.tenantId().equals(command.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!context.isHuman() || !context.canSuspendMerchant()) {
            throw new AuthorizationPermissionDeniedException("merchant:suspend");
        }
        if (!context.allowsMerchant(command.merchantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
    }
}
