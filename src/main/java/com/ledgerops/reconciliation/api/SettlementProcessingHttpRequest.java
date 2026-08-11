package com.ledgerops.reconciliation.api;

import jakarta.validation.constraints.NotNull;

record SettlementProcessingHttpRequest(@NotNull Boolean confirmation) {
}
