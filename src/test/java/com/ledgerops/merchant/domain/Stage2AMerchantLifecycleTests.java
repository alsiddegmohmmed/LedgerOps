package com.ledgerops.merchant.domain;

import com.ledgerops.tenancy.api.TenantReference;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Stage2AMerchantLifecycleTests {

    @Test
    void suspendedMerchantBlocksNewWorkButAllowsCommittedRecovery() {
        Merchant merchant = new Merchant(
                MerchantId.newId(),
                TenantReference.from(UUID.randomUUID()),
                "Acme",
                MerchantStatus.ACTIVE).suspend();

        assertThat(merchant.canCreateNewActivity()).isFalse();
        assertThat(merchant.canCreateCredential()).isFalse();
        assertThat(merchant.canChangeConfiguration()).isFalse();
        assertThat(merchant.allowsCommittedRecovery()).isTrue();
    }
}
