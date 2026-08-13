package com.ledgerops.reporting.application;

public class ReportingNotReadyException extends RuntimeException {

    public ReportingNotReadyException() {
        super("A complete Reporting projection generation is not available");
    }
}
