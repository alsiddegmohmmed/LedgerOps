package com.ledgerops.administration.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.administration.application.TenantActivationPrerequisitesException;
import com.ledgerops.identity.api.PlatformAuthorizationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = AdministrationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class AdministrationProblemHandler {

    @ExceptionHandler(InvalidAdministrationRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidAdministrationRequestException exception) {
        return ApiProblemFactory.create(
                HttpStatus.BAD_REQUEST,
                "Invalid tenant onboarding request",
                exception.getMessage(),
                "invalid-tenant-request",
                "No tenant was created or changed.",
                false,
                "Correct the request values and submit it again."
        );
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ProblemDetail handleMalformedRequest(Exception exception) {
        return ApiProblemFactory.create(
                HttpStatus.BAD_REQUEST,
                "Invalid tenant administration request",
                "The request contains malformed JSON or an invalid value",
                "invalid-tenant-request",
                "No tenant was created or changed.",
                false,
                "Correct the request and submit it again."
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = ApiProblemFactory.create(
                HttpStatus.BAD_REQUEST,
                "Tenant administration request validation failed",
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

    @ExceptionHandler(PlatformAuthorizationException.class)
    ProblemDetail handlePlatformAuthorization(PlatformAuthorizationException exception) {
        return ApiProblemFactory.create(
                HttpStatus.FORBIDDEN,
                "Authorization denied",
                exception.getMessage(),
                "authorization-denied",
                "The tenant was not changed.",
                false,
                "Use an authenticated Platform Admin identity and retry."
        );
    }

    @ExceptionHandler(TenantActivationPrerequisitesException.class)
    ProblemDetail handleActivationPrerequisites(
            TenantActivationPrerequisitesException exception
    ) {
        ProblemDetail problem = ApiProblemFactory.create(
                HttpStatus.CONFLICT,
                "Tenant activation prerequisites are not satisfied",
                exception.getMessage(),
                "tenant-activation-prerequisites-not-satisfied",
                "The tenant was not activated.",
                false,
                "Complete the initial Tenant Admin onboarding and ensure an active Merchant exists."
        );
        problem.setProperty("tenantId", exception.tenant().value());
        problem.setProperty(
                "initialTenantAdminActive",
                exception.identityReadiness().initialTenantAdminActive()
        );
        problem.setProperty(
                "onboardingConsistent",
                exception.identityReadiness().onboardingConsistent()
        );
        problem.setProperty("activeMerchantExists", exception.activeMerchantExists());
        return problem;
    }
}
