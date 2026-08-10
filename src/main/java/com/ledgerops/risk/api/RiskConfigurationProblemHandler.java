package com.ledgerops.risk.api;

import com.ledgerops.risk.application.RiskConfigurationConflictException;
import com.ledgerops.risk.application.RiskConfigurationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RiskConfigurationController.class)
class RiskConfigurationProblemHandler {

    @ExceptionHandler(RiskConfigurationNotFoundException.class)
    ProblemDetail notFound(RiskConfigurationNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Risk configuration not found", exception.getMessage());
    }

    @ExceptionHandler(RiskConfigurationConflictException.class)
    ProblemDetail conflict(RiskConfigurationConflictException exception) {
        return problem(HttpStatus.CONFLICT, "Risk configuration conflict", exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, RiskConfigurationException.class})
    ProblemDetail invalid(RuntimeException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid Risk configuration", exception.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail value = ProblemDetail.forStatusAndDetail(status, detail);
        value.setTitle(title);
        return value;
    }
}
