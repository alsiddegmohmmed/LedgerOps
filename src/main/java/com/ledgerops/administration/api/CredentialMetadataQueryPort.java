package com.ledgerops.administration.api;

public interface CredentialMetadataQueryPort {

    CredentialMetadataResult find(CredentialMetadataQuery query);
}
