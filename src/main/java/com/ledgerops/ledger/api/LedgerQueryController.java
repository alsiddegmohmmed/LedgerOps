package com.ledgerops.ledger.web.api;

import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.ledger.api.LedgerBalanceResponse;
import com.ledgerops.ledger.api.LedgerStatementEntryResponse;
import com.ledgerops.ledger.api.LedgerStatementResponse;
import com.ledgerops.ledger.application.LedgerAccountBalance;
import com.ledgerops.ledger.application.LedgerAccountQueryService;
import com.ledgerops.ledger.application.LedgerStatementQuery;
import com.ledgerops.ledger.domain.LedgerAccountId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/ledger/accounts/{accountId}")
class LedgerQueryController {

    private final LedgerAccountQueryService ledger;
    private final Clock clock;

    LedgerQueryController(LedgerAccountQueryService ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    @GetMapping("/balance")
    ResponseEntity<LedgerBalanceResponse> balance(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @RequestParam(required = false) String asOf,
            HttpServletRequest httpRequest
    ) {
        requireTenantWideRead(tenantId, httpRequest);
        LedgerAccountBalance result = ledger.balance(
                tenantId,
                LedgerAccountId.from(accountId),
                asOf == null || asOf.isBlank() ? clock.instant() : parseInstant(asOf, "asOf"));
        return ResponseEntity.ok(new LedgerBalanceResponse(
                result.accountId().value(),
                result.currency().getCurrencyCode(),
                result.totalDebits(),
                result.totalCredits(),
                result.asOfExclusive()));
    }

    @GetMapping("/statement")
    ResponseEntity<LedgerStatementResponse> statement(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest httpRequest
    ) {
        requireTenantWideRead(tenantId, httpRequest);
        var result = ledger.statement(new LedgerStatementQuery(
                tenantId,
                LedgerAccountId.from(accountId),
                parseInstant(from, "from"),
                parseInstant(to, "to"),
                offset,
                limit));
        return ResponseEntity.ok(new LedgerStatementResponse(
                result.accountId().value(),
                result.currency().getCurrencyCode(),
                result.fromInclusive(),
                result.toExclusive(),
                result.totalDebits(),
                result.totalCredits(),
                result.totalEntries(),
                result.offset(),
                result.limit(),
                result.entries().stream()
                        .map(entry -> new LedgerStatementEntryResponse(
                                entry.transactionId().value(),
                                entry.entryIndex(),
                                entry.sourceReference().sourceType().name(),
                                entry.sourceReference().sourceId(),
                                entry.postedAt(),
                                entry.direction().name(),
                                entry.amount().amount(),
                                entry.amount().currency().getCurrencyCode()))
                        .toList()));
    }

    private void requireTenantWideRead(UUID tenantId, HttpServletRequest request) {
        var authorization = AuthorizedRequestContextRequest.required(request);
        if (!authorization.tenantId().equals(tenantId)) {
            throw new com.ledgerops.identity.api.AuthorizationResourceNotFoundException();
        }
        if (!authorization.isHuman() || !authorization.canReadLedger()) {
            throw new AuthorizationPermissionDeniedException("ledger:read");
        }
        if (!authorization.isTenantWide()) {
            throw new AuthorizationPermissionDeniedException(
                    "ledger:read requires Tenant-wide scope for unowned Ledger accounts");
        }
    }

    private static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant", exception);
        }
    }
}
