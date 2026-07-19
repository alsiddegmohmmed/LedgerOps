package com.ledgerops.payment.api;

import com.ledgerops.payment.application.CreatePaymentCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

record CreatePaymentRequest(
        @NotNull(message = "tenantId is required")
        UUID tenantId,

        @NotNull(message = "merchantId is required")
        UUID merchantId,

        @NotNull(message = "customerId is required")
        UUID customerId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0", inclusive = false, message = "amount must be positive")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a three-letter uppercase code")
        String currency,

        @NotBlank(message = "paymentMethodCategory is required")
        String paymentMethodCategory,

        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey
) {

    CreatePaymentCommand toCommand() {
        return new CreatePaymentCommand(
                tenantId,
                merchantId,
                customerId,
                amount,
                currency,
                paymentMethodCategory,
                idempotencyKey
        );
    }
}
