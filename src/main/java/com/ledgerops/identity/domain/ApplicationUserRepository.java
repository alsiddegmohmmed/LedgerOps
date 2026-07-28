package com.ledgerops.identity.domain;

import java.util.Optional;

public interface ApplicationUserRepository {

    ApplicationUser save(ApplicationUser applicationUser);

    Optional<ApplicationUser> findById(ApplicationUserId id);

    Optional<ApplicationUser> findByKeycloakIdentity(KeycloakIdentity keycloakIdentity);
}
