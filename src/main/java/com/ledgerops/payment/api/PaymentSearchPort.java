package com.ledgerops.payment.api;

public interface PaymentSearchPort {

    PaymentSearchPage findPage(PaymentSearchQuery query);
}
