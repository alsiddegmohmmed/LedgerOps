package com.ledgerops.payment.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.risk.api.RiskDecision;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/payments")
class PaymentQueryController {

    private final PaymentSearchPort payments;

    PaymentQueryController(PaymentSearchPort payments) {
        this.payments = payments;
    }

    @GetMapping
    ResponseEntity<PaymentSearchPage> findPage(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String platformId,
            @RequestParam(required = false) String merchantReference,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String minAmount,
            @RequestParam(required = false) String maxAmount,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String riskDecision,
            @RequestParam(required = false) String reconciliationStatus,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest httpRequest
    ) {
        var authorization = AuthorizedRequestContextRequest.required(httpRequest);
        return ResponseEntity.ok(payments.findPage(new PaymentSearchQuery(
                tenantId,
                optionalUuid(platformId, "platformId"),
                optionalUuid(merchantReference, "merchantReference"),
                optionalText(providerId),
                optionalUuid(customerId, "customerId"),
                optionalInstant(from, "from"),
                optionalInstant(to, "to"),
                optionalDecimal(minAmount, "minAmount"),
                optionalDecimal(maxAmount, "maxAmount"),
                optionalEnum(state, PaymentStatus.class, "state"),
                optionalEnum(riskDecision, RiskDecision.class, "riskDecision"),
                optionalText(reconciliationStatus),
                limit,
                cursor,
                authorization
        )));
    }

    private static UUID optionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }

    private static Instant optionalInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant", exception);
        }
    }

    private static BigDecimal optionalDecimal(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be a decimal number", exception);
        }
    }

    private static <T extends Enum<T>> T optionalEnum(
            String value,
            Class<T> type,
            String field
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " contains an unsupported value", exception);
        }
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
