package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.reconciliation.application.ObjectStoragePort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

@Configuration
@EnableConfigurationProperties(SettlementStorageProperties.class)
class SettlementStorageConfiguration {

    @Bean
    S3Client settlementS3Client(SettlementStorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccess())
                        .build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Bean
    ObjectStoragePort settlementObjectStorage(
            S3Client settlementS3Client,
            SettlementStorageProperties properties
    ) {
        return new S3ObjectStorage(settlementS3Client, properties.bucket());
    }
}
