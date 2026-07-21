package com.ledgerops.simulator;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
final class SimulatorProblemHandler {
    @ExceptionHandler(SimulatorProblemException.class)
    ResponseEntity<ProblemDetail> handle(SimulatorProblemException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                exception.status(), exception.getMessage());
        problem.setType(URI.create("https://ledgerops.dev/problems/"
                + exception.code().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        problem.setTitle(exception.code());
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(exception.status()).body(problem);
    }
}
