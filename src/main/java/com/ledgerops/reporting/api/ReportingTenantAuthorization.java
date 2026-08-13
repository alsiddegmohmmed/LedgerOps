package com.ledgerops.reporting.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.domain.Permission;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Shared authorization and Merchant-scope rules for Reporting read endpoints. */
final class ReportingTenantAuthorization {

    private ReportingTenantAuthorization() {
    }

    static AuthorizedRequestContext required(
            UUID tenantId,
            List<String> requestedMerchantIds,
            HttpServletRequest request
    ) {
        AuthorizedRequestContext authorization = AuthorizedRequestContextRequest.required(request);
        if (!tenantId.equals(authorization.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!authorization.canReadReports()) {
            throw new AuthorizationPermissionDeniedException("report:read");
        }
        Set<UUID> requested = parseMerchantIds(requestedMerchantIds);
        if (!authorization.isTenantWide() && !authorization.merchantIds().containsAll(requested)) {
            throw new AuthorizationResourceNotFoundException();
        }
        return authorization;
    }

    static AuthorizedRequestContext requiredExport(
            UUID tenantId,
            List<String> requestedMerchantIds,
            HttpServletRequest request
    ) {
        AuthorizedRequestContext authorization = required(tenantId, requestedMerchantIds, request);
        if (!authorization.hasPermission(Permission.REPORT_EXPORT)) {
            throw new AuthorizationPermissionDeniedException("report:export");
        }
        return authorization;
    }

    static Set<UUID> effectiveMerchantIds(
            AuthorizedRequestContext authorization,
            List<String> requestedMerchantIds
    ) {
        Set<UUID> requested = parseMerchantIds(requestedMerchantIds);
        return requested.isEmpty() && !authorization.isTenantWide()
                ? authorization.merchantIds()
                : requested;
    }

    static Set<UUID> parseMerchantIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream().map(value -> {
            try {
                return UUID.fromString(value.trim());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("merchantId must be a UUID", exception);
            }
        }).collect(Collectors.toUnmodifiableSet());
    }
}
