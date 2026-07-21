package com.ledgerops.payment.application;

import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;

final class CanonicalJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CanonicalJson() {
    }

    static String object(LinkedHashMap<String, Object> fields) {
        try {
            return JSON.writeValueAsString(fields);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot serialize canonical JSON", exception);
        }
    }
}
