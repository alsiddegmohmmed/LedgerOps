package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.reconciliation.application.ObjectStoragePort;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class S3ObjectStorage implements ObjectStoragePort {

    private final S3Client client;
    private final String bucket;

    S3ObjectStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public StoredObject putIfAbsent(String key, Path content, String contentType) {
        try {
            var existing = client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).build());
            return new StoredObject(key, existing.contentLength());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) throw exception;
        }
        ensureBucket();
        try {
            long size = Files.size(content);
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .contentLength(size)
                            .build(),
                    RequestBody.fromFile(content));
            return new StoredObject(key, size);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Settlement object could not be read", exception);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException exception) {
            throw new IllegalStateException("Settlement object does not exist", exception);
        }
    }

    private void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) throw exception;
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }
}
