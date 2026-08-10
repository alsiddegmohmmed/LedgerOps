package com.ledgerops.ledger.web.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.ledger.application.LedgerAccountNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LedgerQueryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class LedgerQueryProblemHandler {

    @ExceptionHandler(LedgerAccountNotFoundException.class)
    ProblemDetail handleNotFound(LedgerAccountNotFoundException exception) {
        return ApiProblemFactory.create(
                HttpStatus.NOT_FOUND,
                "Ledger account not found",
                "The requested Ledger account is unavailable in the Tenant scope",
                "ledger-account-not-found",
                "No Ledger state was changed.",
                false,
                "Check the Tenant and account identifiers.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidRequest(IllegalArgumentException exception) {
        return ApiProblemFactory.create(
                HttpStatus.BAD_REQUEST,
                "Invalid Ledger query",
                exception.getMessage(),
                "invalid-ledger-query",
                "No Ledger state was changed.",
                false,
                "Correct the date or page bounds and retry.");
    }
}
