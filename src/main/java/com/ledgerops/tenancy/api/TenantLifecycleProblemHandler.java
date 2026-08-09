package com.ledgerops.tenancy.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.tenancy.application.DuplicateTenantNameException;
import com.ledgerops.tenancy.application.TenantLifecycleException;
import com.ledgerops.tenancy.application.TenantNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class TenantLifecycleProblemHandler {

    @ExceptionHandler(DuplicateTenantNameException.class)
    ProblemDetail handleDuplicateName(DuplicateTenantNameException exception) {
        ProblemDetail problem = ApiProblemFactory.create(
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

    @ExceptionHandler(TenantNotFoundException.class)
    ProblemDetail handleNotFound(TenantNotFoundException exception) {
        ProblemDetail problem = ApiProblemFactory.create(
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

    @ExceptionHandler(TenantLifecycleException.class)
    ProblemDetail handleInvalidLifecycle(TenantLifecycleException exception) {
        ProblemDetail problem = ApiProblemFactory.create(
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
}
