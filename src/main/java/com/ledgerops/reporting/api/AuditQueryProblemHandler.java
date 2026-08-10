package com.ledgerops.reporting.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.audit.api.InvalidAuditCursorException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuditQueryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuditQueryProblemHandler {

    @ExceptionHandler(InvalidAuditCursorException.class)
    ProblemDetail handleInvalidCursor(InvalidAuditCursorException exception) {
        return ApiProblemFactory.create(
                HttpStatus.BAD_REQUEST,
                "Invalid Audit page cursor",
                exception.getMessage(),
                "invalid-audit-cursor",
                "No Audit state was changed.",
                false,
                "Restart the Audit search without the cursor.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidQuery(IllegalArgumentException exception) {
        return ApiProblemFactory.create(
                HttpStatus.BAD_REQUEST,
                "Invalid Audit search",
                exception.getMessage(),
                "invalid-audit-search",
                "No Audit state was changed.",
                false,
                "Correct the filters and retry.");
    }
}
