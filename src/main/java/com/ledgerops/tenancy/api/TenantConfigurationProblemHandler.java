package com.ledgerops.tenancy.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.tenancy.application.TenantConfigurationNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = TenantConfigurationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class TenantConfigurationProblemHandler {

    @ExceptionHandler(InvalidTenantConfigurationRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidTenantConfigurationRequestException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid Tenant configuration request",
                exception.getMessage(),
                "invalid-tenant-configuration-request",
                "No Tenant configuration was changed.",
                false,
                "Correct the configuration values and submit the request again."
        );
    }

    @ExceptionHandler(TenantConfigurationNotFoundException.class)
    ProblemDetail handleNotFound(TenantConfigurationNotFoundException exception) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "Tenant configuration not found",
                exception.getMessage(),
                "tenant-configuration-not-found",
                "No Tenant configuration was read.",
                false,
                "Create the first configuration version with PUT."
        );
        problem.setProperty("tenantId", exception.tenantId().value());
        return problem;
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            HttpMessageConversionException.class,
            MethodArgumentTypeMismatchException.class
    })
    ProblemDetail handleMalformedRequest(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid Tenant configuration request",
                "The request contains malformed JSON or an invalid path value",
                "invalid-tenant-configuration-request",
                "No Tenant configuration was changed.",
                false,
                "Correct the request and submit it again."
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Tenant configuration validation failed",
                "One or more configuration fields are invalid",
                "tenant-configuration-validation",
                "No Tenant configuration was changed.",
                false,
                "Correct the listed fields and submit the request again."
        );
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(
                error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleStateConflict(IllegalStateException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Tenant configuration cannot change",
                exception.getMessage(),
                "tenant-configuration-state-conflict",
                "No Tenant configuration was changed.",
                false,
                "Refresh the Tenant status and retry only when configuration changes are allowed."
        );
    }

    private ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String type,
            String effect,
            boolean retryable,
            String nextAction
    ) {
        return ApiProblemFactory.create(status, title, detail, type, effect, retryable, nextAction);
    }
}
