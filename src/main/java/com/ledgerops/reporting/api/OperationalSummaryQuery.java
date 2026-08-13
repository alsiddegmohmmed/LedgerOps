package com.ledgerops.reporting.api;

public interface OperationalSummaryQuery {

    OperationalSummaryResponse findSummary(OperationalSummaryRequest request);

    OperationalSummaryRecordPage findRecords(OperationalSummaryRecordsRequest request);
}
