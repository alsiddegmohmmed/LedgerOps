package com.ledgerops.casework.application;

import java.util.UUID;

public class CaseNotFoundException extends RuntimeException {
    public CaseNotFoundException(UUID caseId) { super("Case not found: " + caseId); }
}
