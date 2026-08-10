package com.ledgerops.risk.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

record RiskConfigurationHttpRequest(
        @Min(1) @Max(99) int reviewThreshold,
        @Min(2) @Max(100) int rejectThreshold,
        @NotEmpty List<@Valid RiskRuleHttpRequest> rules,
        Long expectedVersion,
        @AssertTrue(message = "confirmation must be true") boolean confirmation,
        @NotBlank @Size(max = 512) String reason
) {
    List<RiskRuleConfiguration> toConfigurations() {
        return rules.stream().map(RiskRuleHttpRequest::toConfiguration).toList();
    }

    record RiskRuleHttpRequest(
            @NotBlank String currency,
            BigDecimal amountThreshold,
            @Min(1) @Max(100) int scoreContribution,
            boolean enabled
    ) {
        RiskRuleConfiguration toConfiguration() {
            return new RiskRuleConfiguration(currency, amountThreshold, scoreContribution, enabled);
        }
    }
}
