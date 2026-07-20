package com.ledgerops;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public final class ApiProblemFactory {

    private ApiProblemFactory() {
    }

    public static ProblemDetail create(
            HttpStatus status,
            String title,
            String detail,
            String type,
            String effect,
            boolean retryable,
            String nextAction
    ) {
        Objects.requireNonNull(status, "HTTP status must not be null");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:ledgerops:problem:" + type));
        problem.setProperty("code", type.replace('-', '_').toUpperCase(Locale.ROOT));
        problem.setProperty(
                "correlationId",
                MDC.get(RequestCorrelationFilter.CORRELATION_ID)
        );
        problem.setProperty("effect", effect);
        problem.setProperty("retryable", retryable);
        problem.setProperty("nextAction", nextAction);
        return problem;
    }
}
