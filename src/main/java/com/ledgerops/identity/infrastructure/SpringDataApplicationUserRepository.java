package com.ledgerops.identity.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataApplicationUserRepository
        extends JpaRepository<ApplicationUserJpaEntity, UUID> {

    Optional<ApplicationUserJpaEntity> findByIssuerAndSubject(String issuer, String subject);
}
