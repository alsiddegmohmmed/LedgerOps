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
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ReportingEventsControllerAuthorizationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ALLOWED_MERCHANT = UUID.randomUUID();
    private static final UUID OUTSIDE_MERCHANT = UUID.randomUUID();

    @Test
    void rejectsAnOutOfScopeMerchantWithoutOpeningAStream() {
        ReportingEventsController controller = new ReportingEventsController(
                mock(ReportingProjectionEventQuery.class));

        assertThatThrownBy(() -> controller.stream(
                TENANT,
                java.util.List.of(OUTSIDE_MERCHANT.toString()),
                null,
                request(context(ScopeMode.MERCHANT_SET, Set.of(ALLOWED_MERCHANT),
                        Set.of(Permission.REPORT_READ)))))
                .isInstanceOf(AuthorizationResourceNotFoundException.class);
    }

    @Test
    void requiresReportReadPermission() {
        ReportingEventsController controller = new ReportingEventsController(
                mock(ReportingProjectionEventQuery.class));

        assertThatThrownBy(() -> controller.stream(
                TENANT, null, null,
                request(context(ScopeMode.TENANT_WIDE, Set.of(), Set.of(Permission.PAYMENT_READ)))))
                .isInstanceOf(AuthorizationPermissionDeniedException.class);
    }

    @Test
    void rejectsMalformedLastEventId() {
        ReportingEventsController controller = new ReportingEventsController(
                mock(ReportingProjectionEventQuery.class));

        assertThatIllegalArgumentException().isThrownBy(() -> controller.stream(
                TENANT, null, "not-a-number",
                request(context(ScopeMode.TENANT_WIDE, Set.of(), Set.of(Permission.REPORT_READ)))));
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
