package com.ledgerops.tenancy.api;

import com.ledgerops.RequestCorrelationFilter;
import com.ledgerops.tenancy.application.DuplicateTenantNameException;
import com.ledgerops.tenancy.application.TenantLifecycleException;
import com.ledgerops.tenancy.application.TenantNotFoundException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice(assignableTypes = TenantController.class)
class TenantProblemHandler {

    @ExceptionHandler(TenantNotFoundException.class)
    ProblemDetail handleNotFound(TenantNotFoundException exception) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "Tenant not found",
                exception.getMessage(),
                "tenant-not-found"
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
                "tenant-name-conflict"
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
                "invalid-tenant-transition"
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
                "invalid-tenant-request"
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
                "invalid-tenant-request"
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Tenant request validation failed",
                "One or more request fields are invalid",
                "tenant-request-validation"
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
            String type
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:ledgerops:problem:" + type));
        problem.setProperty("code", type.replace('-', '_').toUpperCase(Locale.ROOT));
        problem.setProperty("traceId", MDC.get(RequestCorrelationFilter.TRACE_ID));
        return problem;
    }
}
