package com.ledgerops.payment.api;

public interface PaymentReconciliationQuery {

    PaymentReconciliationPage findPage(PaymentReconciliationPageRequest request);
}
