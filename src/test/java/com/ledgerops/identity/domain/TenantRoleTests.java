package com.ledgerops.identity.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantRoleTests {

    @Test
    void enforcesApprovedRoleScopeModes() {
        assertThatThrownBy(() -> TenantRole.TENANT_ADMIN.validateScope(ScopeMode.MERCHANT_SET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantRole.MERCHANT_ADMIN.validateScope(ScopeMode.TENANT_WIDE))
                .isInstanceOf(IllegalArgumentException.class);

        TenantRole.OPERATIONS_AGENT.validateScope(ScopeMode.TENANT_WIDE);
        TenantRole.OPERATIONS_AGENT.validateScope(ScopeMode.MERCHANT_SET);
    }
}
