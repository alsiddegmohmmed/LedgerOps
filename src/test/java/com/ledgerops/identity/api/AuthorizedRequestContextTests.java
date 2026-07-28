package com.ledgerops.identity.api;

import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AuthorizedRequestContextTests {

    @Test
    void copiesAuthorizationCollectionsAndExposesExplicitPermissionChecks() {
        Set<UUID> merchantIds = new HashSet<>();
        Set<Permission> permissions = new HashSet<>();
        UUID merchantId = UUID.randomUUID();
        merchantIds.add(merchantId);
        permissions.add(Permission.PAYMENT_READ);

        AuthorizedRequestContext context = new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                ScopeMode.MERCHANT_SET,
                merchantIds,
                permissions,
                "correlation-1"
        );

        merchantIds.clear();
        permissions.clear();

        assertThat(context.merchantIds()).containsExactly(merchantId);
        assertThat(context.hasPermission(Permission.PAYMENT_READ)).isTrue();
        assertThat(context.hasPermission(Permission.PAYMENT_RETRY)).isFalse();
    }

    @Test
    void rejectsEmptyMerchantSetForMerchantScopedContext() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                ScopeMode.MERCHANT_SET,
                Set.of(),
                Set.of(Permission.PAYMENT_READ),
                "correlation-1"
        ));
    }

    @Test
    void requiresIdentitySpecificPrincipalEvidence() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthorizedRequestContext(
                PrincipalType.SERVICE,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                ScopeMode.TENANT_WIDE,
                Set.of(),
                Set.of(),
                "correlation-1"
        ));
    }
}
