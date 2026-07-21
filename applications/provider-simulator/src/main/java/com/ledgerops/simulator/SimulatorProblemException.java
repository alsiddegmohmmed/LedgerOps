package com.ledgerops.simulator;

import org.springframework.http.HttpStatus;

final class SimulatorProblemException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    SimulatorProblemException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
