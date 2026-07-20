package com.ledgerops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class UnhandledApiProblemHandlerTests {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void unexpectedFailureReturnsSafeCorrelatedProblemWithoutInternalDetail() {
        MDC.put(RequestCorrelationFilter.CORRELATION_ID, "safe-correlation-id");
        RuntimeException failure = new RuntimeException(
                "jdbc:postgresql://secret-host SELECT password FROM users"
        );

        ProblemDetail problem = new UnhandledApiProblemHandler()
                .handleUnexpectedFailure(failure);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), problem.getStatus());
        assertEquals("Unexpected internal error", problem.getTitle());
        assertEquals("UNEXPECTED_INTERNAL_ERROR", problem.getProperties().get("code"));
        assertEquals(
                "safe-correlation-id",
                problem.getProperties().get("correlationId")
        );
        assertEquals(false, problem.getProperties().get("retryable"));
        String externalText = problem.toString().toLowerCase();
        assertFalse(externalText.contains("postgresql"));
        assertFalse(externalText.contains("password"));
        assertFalse(externalText.contains("select"));
    }
}
