package com.ledgerops.administration.credentials.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.administration.application.CredentialAdministrationBlockedException;
import com.ledgerops.identity.api.ServiceCredentialProvisioningFailedException;
import com.ledgerops.identity.api.ServiceCredentialRevocationFailedException;
import com.ledgerops.identity.api.ServiceCredentialRotationFailedException;
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

@RestControllerAdvice(assignableTypes = {
        CredentialController.class,
        CredentialMetadataController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
class CredentialProblemHandler {

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ProblemDetail handleMalformedRequest(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid credential action request",
                "The request contains malformed JSON or an invalid value",
                "invalid-credential-request",
                "No credential state was changed.",
                false,
                "Correct the request and submit it again."
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Credential action request validation failed",
                "One or more request fields are invalid",
                "credential-request-validation",
                "No credential state was changed.",
                false,
                "Correct the listed fields and submit the request again."
        );
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(
                error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        return withErrors(problem, errors);
    }

    @ExceptionHandler(CredentialAdministrationBlockedException.class)
    ProblemDetail handleBlocked(CredentialAdministrationBlockedException exception) {
        return problem(
                HttpStatus.FORBIDDEN,
                "Credential administration is blocked",
                "Credential creation and rotation require an active Tenant and Merchant",
                "credential-administration-blocked",
                "No credential state was changed.",
                false,
                "Activate the Tenant and Merchant, then retry the action."
        );
    }

    @ExceptionHandler(ServiceCredentialProvisioningFailedException.class)
    ProblemDetail handleProvisioningFailure(ServiceCredentialProvisioningFailedException exception) {
        ProblemDetail problem = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Credential provisioning is incomplete",
                "The provisioning operation is durable and can be retried",
                "credential-provisioning-unavailable",
                "The credential remains in a failed or incomplete provisioning state; no secret was disclosed.",
                true,
                "Inspect the provisioning operation and retry it."
        );
        problem.setProperty("operationId", exception.operationId());
        problem.setProperty("failureCode", exception.failureCode());
        return problem;
    }

    @ExceptionHandler(ServiceCredentialRotationFailedException.class)
    ProblemDetail handleRotationFailure(ServiceCredentialRotationFailedException exception) {
        ProblemDetail problem = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Credential rotation cleanup is incomplete",
                "The replacement and local revocation are durable; external cleanup can be retried",
                "credential-rotation-cleanup-unavailable",
                "The replacement credential may be active and the previous credential is locally revoked; no secret was disclosed.",
                true,
                "Retry external rotation cleanup before using the previous credential."
        );
        problem.setProperty("replacementCredentialId", exception.replacementCredentialId());
        problem.setProperty("oldCredentialId", exception.oldCredentialId());
        problem.setProperty("failureCode", exception.failureCode());
        return problem;
    }

    @ExceptionHandler(ServiceCredentialRevocationFailedException.class)
    ProblemDetail handleRevocationFailure(ServiceCredentialRevocationFailedException exception) {
        ProblemDetail problem = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Credential revocation cleanup is incomplete",
                "Local revocation is durable and external cleanup can be retried",
                "credential-revocation-cleanup-unavailable",
                "The credential is locally revoked; no secret was disclosed.",
                true,
                "Retry external revocation cleanup."
        );
        problem.setProperty("credentialId", exception.credentialId());
        problem.setProperty("failureCode", exception.failureCode());
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleConflict(IllegalStateException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Credential action conflicts with current state",
                "The requested credential action is not valid for the current state",
                "credential-state-conflict",
                "No additional credential state was changed.",
                false,
                "Refresh the credential state and retry only if the action is still valid."
        );
    }

    private ProblemDetail withErrors(ProblemDetail problem, Map<String, String> errors) {
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
