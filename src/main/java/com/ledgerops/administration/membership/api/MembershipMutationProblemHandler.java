package com.ledgerops.administration.membership.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.identity.api.InvitationNotFoundException;
import com.ledgerops.identity.api.InvitationStateConflictException;
import com.ledgerops.identity.api.MembershipStateConflictException;
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
        InvitationRevocationController.class,
        InvitationAdministrationController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
class MembershipMutationProblemHandler {

    @ExceptionHandler(InvitationNotFoundException.class)
    ProblemDetail handleNotFound(InvitationNotFoundException exception) {
        return problem(
                HttpStatus.NOT_FOUND,
                "Invitation not found",
                "The requested invitation is unavailable",
                "invitation-not-found",
                "No membership or invitation state was changed.",
                false,
                "Refresh the membership list and retry only for a pending invitation."
        );
    }

    @ExceptionHandler(InvitationStateConflictException.class)
    ProblemDetail handleMembershipState(InvitationStateConflictException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Invitation cannot be revoked",
                exception.getMessage(),
                "invitation-state-conflict",
                "No membership or invitation state was changed.",
                false,
                "Refresh the membership state before retrying."
        );
    }

    @ExceptionHandler(MembershipStateConflictException.class)
    ProblemDetail handleMembershipTransition(MembershipStateConflictException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Membership role change is not allowed",
                exception.getMessage(),
                "membership-state-conflict",
                "No membership or invitation state was changed.",
                false,
                "Refresh the membership state and submit a permitted role set."
        );
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ProblemDetail handleMalformedRequest(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid invitation revocation request",
                "The request contains malformed JSON or an invalid value",
                "invalid-invitation-revocation-request",
                "No membership or invitation state was changed.",
                false,
                "Correct the request and submit it again."
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Invitation revocation validation failed",
                "One or more request fields are invalid",
                "invitation-revocation-validation",
                "No membership or invitation state was changed.",
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
        return ApiProblemFactory.create(status, title, detail, type, effect, retryable, nextAction);
    }
}
