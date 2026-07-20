package com.ledgerops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class UnhandledApiProblemHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            UnhandledApiProblemHandler.class
    );

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleUnknownRoute(NoResourceFoundException exception) {
        return ApiProblemFactory.create(
                HttpStatus.NOT_FOUND,
                "API resource not found",
                "The requested API resource does not exist",
                "api-resource-not-found",
                "No resource was read or changed.",
                false,
                "Check the request path against the published OpenAPI contract."
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail handleUnsupportedMethod(
            HttpRequestMethodNotSupportedException exception
    ) {
        return ApiProblemFactory.create(
                HttpStatus.METHOD_NOT_ALLOWED,
                "HTTP method not supported",
                "The requested HTTP method is not supported for this resource",
                "http-method-not-supported",
                "No resource was changed.",
                false,
                "Use a method documented for this path in the OpenAPI contract."
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception
    ) {
        return ApiProblemFactory.create(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Media type not supported",
                "The request content type is not supported",
                "media-type-not-supported",
                "No resource was changed.",
                false,
                "Send the request using application/json."
        );
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedFailure(Exception exception) {
        LOGGER.error(
                "Unexpected API failure correlationId={}",
                org.slf4j.MDC.get(RequestCorrelationFilter.CORRELATION_ID),
                exception
        );
        return ApiProblemFactory.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected internal error",
                "The request could not be completed safely",
                "unexpected-internal-error",
                "The final effect could not be confirmed from this response.",
                false,
                "Verify the resource state, then contact support with the correlationId."
        );
    }
}
