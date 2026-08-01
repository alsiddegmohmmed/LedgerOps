package com.ledgerops.tenancy.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Stage2ATenantPrerequisiteTests {

    @Test
    void activationFactsRequireEveryApprovedPrerequisite() {
        assertThat(new TenantActivationPrerequisites(true, true, true).satisfied()).isTrue();
        assertThat(new TenantActivationPrerequisites(false, true, true).satisfied()).isFalse();
        assertThat(new TenantActivationPrerequisites(true, false, true).satisfied()).isFalse();
        assertThat(new TenantActivationPrerequisites(true, true, false).satisfied()).isFalse();
    }
}
