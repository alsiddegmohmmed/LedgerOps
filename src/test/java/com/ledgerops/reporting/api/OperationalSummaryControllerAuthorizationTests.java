package com.ledgerops.reporting.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationalSummaryControllerAuthorizationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ALLOWED_MERCHANT = UUID.randomUUID();
    private static final UUID OUTSIDE_MERCHANT = UUID.randomUUID();
    private static final String FROM = "2026-08-13T00:00:00Z";
    private static final String TO = "2026-08-14T00:00:00Z";

    @Test
    void merchantScopedCallerDefaultsToItsCompleteMerchantSet() {
        OperationalSummaryQuery query = mock(OperationalSummaryQuery.class);
        when(query.findSummary(any())).thenReturn(null);
        OperationalSummaryController controller = new OperationalSummaryController(query);
        MockHttpServletRequest http = request(context(ScopeMode.MERCHANT_SET,
                Set.of(ALLOWED_MERCHANT), Set.of(Permission.REPORT_READ)));

        controller.findSummary(TENANT, FROM, TO, null, http);

        var captured = org.mockito.ArgumentCaptor.forClass(OperationalSummaryRequest.class);
        verify(query).findSummary(captured.capture());
        assertEquals(Set.of(ALLOWED_MERCHANT), captured.getValue().merchantIds());
    }

    @Test
    void outOfScopeMerchantIsNotSilentlyRemoved() {
        OperationalSummaryController controller = new OperationalSummaryController(mock(OperationalSummaryQuery.class));
        MockHttpServletRequest http = request(context(ScopeMode.MERCHANT_SET,
                Set.of(ALLOWED_MERCHANT), Set.of(Permission.REPORT_READ)));

        assertThrows(AuthorizationResourceNotFoundException.class, () -> controller.findSummary(
                TENANT, FROM, TO, java.util.List.of(OUTSIDE_MERCHANT.toString()), http));
    }

    @Test
    void reportPermissionIsRequired() {
        OperationalSummaryController controller = new OperationalSummaryController(mock(OperationalSummaryQuery.class));
        MockHttpServletRequest http = request(context(ScopeMode.TENANT_WIDE,
                Set.of(), Set.of(Permission.PAYMENT_READ)));

        assertThrows(AuthorizationPermissionDeniedException.class, () -> controller.findSummary(
                TENANT, FROM, TO, null, http));
    }

    @Test
    void dateOnlyPeriodIsRejectedBeforeQueryExecution() {
        OperationalSummaryQuery query = mock(OperationalSummaryQuery.class);
        OperationalSummaryController controller = new OperationalSummaryController(query);
        MockHttpServletRequest http = request(context(ScopeMode.TENANT_WIDE,
                Set.of(), Set.of(Permission.REPORT_READ)));

        assertThrows(IllegalArgumentException.class, () -> controller.findSummary(
                TENANT, "2026-08-13", TO, null, http));
    }

    private static MockHttpServletRequest request(AuthorizedRequestContext context) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthorizedRequestContext.class.getName(), context);
        return request;
    }

    private static AuthorizedRequestContext context(
            ScopeMode scopeMode,
            Set<UUID> merchants,
            Set<Permission> permissions
    ) {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN, UUID.randomUUID(), null, TENANT, scopeMode,
                merchants, permissions, UUID.randomUUID().toString());
    }
}
