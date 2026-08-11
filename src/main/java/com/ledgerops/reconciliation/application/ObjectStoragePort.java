package com.ledgerops.reconciliation.application;

import java.io.InputStream;
import java.nio.file.Path;

public interface ObjectStoragePort {

    StoredObject putIfAbsent(String key, Path content, String contentType);

    InputStream open(String key);

    record StoredObject(String key, long size) {
    }
}
