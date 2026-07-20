package com.ledgerops.tenancy.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.tenancy.application.DuplicateTenantNameException;
import com.ledgerops.tenancy.application.TenantLifecycleException;
import com.ledgerops.tenancy.application.TenantNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = TenantController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class TenantProblemHandler {

    @ExceptionHandler(TenantNotFoundException.class)
    ProblemDetail handleNotFound(TenantNotFoundException exception) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "Tenant not found",
                exception.getMessage(),
                "tenant-not-found",
                "No tenant was read or changed.",
                false,
                "Verify the tenantId before retrying."
        );
        problem.setProperty("tenantId", exception.tenantId().value());
        return problem;
    }

    @ExceptionHandler(DuplicateTenantNameException.class)
    ProblemDetail handleDuplicateName(DuplicateTenantNameException exception) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "Tenant name conflict",
                exception.getMessage(),
                "tenant-name-conflict",
                "No tenant was created.",
                false,
                "Choose another tenant name or read the existing tenant."
        );
        problem.setProperty("tenantName", exception.tenantName());
        return problem;
    }

    @ExceptionHandler(TenantLifecycleException.class)
    ProblemDetail handleInvalidLifecycle(TenantLifecycleException exception) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "Invalid tenant lifecycle transition",
                exception.getMessage(),
                "invalid-tenant-transition",
                "The tenant status was not changed.",
                false,
                "Refresh the tenant and choose a transition allowed from its current status."
        );
        problem.setProperty("tenantId", exception.tenantId().value());
        problem.setProperty("targetStatus", exception.targetStatus());
        return problem;
    }

    @ExceptionHandler(InvalidTenantRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidTenantRequestException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid tenant request",
                exception.getMessage(),
                "invalid-tenant-request",
                "No tenant was created or changed.",
                false,
                "Correct the request values and submit it again."
        );
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    ProblemDetail handleMalformedRequest(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid tenant request",
                "The request contains malformed JSON or an invalid value",
                "invalid-tenant-request",
                "No tenant was created or changed.",
                false,
                "Correct the JSON and submit the request again."
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Tenant request validation failed",
                "One or more request fields are invalid",
                "tenant-request-validation",
                "No tenant was created or changed.",
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

    private ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String type,
            String effect,
            boolean retryable,
            String nextAction
    ) {
        return ApiProblemFactory.create(
                status,
                title,
                detail,
                type,
                effect,
                retryable,
                nextAction
        );
    }
}
