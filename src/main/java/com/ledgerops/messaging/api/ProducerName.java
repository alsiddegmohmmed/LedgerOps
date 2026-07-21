package com.ledgerops.messaging.api;

public enum ProducerName {
    PAYMENT("payment"),
    PROVIDER("provider");

    private final String value;

    ProducerName(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
