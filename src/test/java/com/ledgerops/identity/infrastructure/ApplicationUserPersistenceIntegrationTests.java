package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.ApplicationUser;
import com.ledgerops.identity.domain.ApplicationUserAlreadyDeactivatedException;
import com.ledgerops.identity.domain.ApplicationUserIdentityConflictException;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.ApplicationUserRepository;
import com.ledgerops.identity.domain.ApplicationUserStatus;
import com.ledgerops.identity.domain.KeycloakIdentity;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ApplicationUserPersistenceIntegrationTests {

    @Autowired
    private ApplicationUserRepository applicationUserRepository;

    @Test
    void savesAndLoadsApplicationUserByIdAndKeycloakIdentity() {
        ApplicationUser user = user();

        applicationUserRepository.save(user);

        ApplicationUser loadedById = applicationUserRepository.findById(user.id()).orElseThrow();
        ApplicationUser loadedByIdentity = applicationUserRepository
                .findByKeycloakIdentity(user.keycloakIdentity())
                .orElseThrow();

        assertEquals(user.id(), loadedById.id());
        assertEquals(user.keycloakIdentity(), loadedById.keycloakIdentity());
        assertEquals(ApplicationUserStatus.ACTIVE, loadedById.status());
        assertEquals(user.id(), loadedByIdentity.id());
    }

    @Test
    void persistsTerminalDeactivationWithoutChangingIdentity() {
        ApplicationUser user = user();
        applicationUserRepository.save(user);

        ApplicationUser deactivated = user.deactivate();
        applicationUserRepository.save(deactivated);

        ApplicationUser loaded = applicationUserRepository.findById(user.id()).orElseThrow();

        assertEquals(ApplicationUserStatus.DEACTIVATED, loaded.status());
        assertEquals(user.keycloakIdentity(), loaded.keycloakIdentity());
        assertThrows(ApplicationUserAlreadyDeactivatedException.class, loaded::deactivate);
    }

    @Test
    void rejectsDuplicateKeycloakIdentityInsteadOfCreatingAnotherUser() {
        ApplicationUser first = user();
        ApplicationUser duplicate = ApplicationUser.create(
                ApplicationUserId.newId(),
                first.keycloakIdentity()
        );
        applicationUserRepository.save(first);

        assertThrows(
                ApplicationUserIdentityConflictException.class,
                () -> applicationUserRepository.save(duplicate)
        );

        assertTrue(applicationUserRepository.findById(duplicate.id()).isEmpty());
    }

    private ApplicationUser user() {
        return ApplicationUser.create(
                ApplicationUserId.newId(),
                new KeycloakIdentity(
                        "https://keycloak.example/realms/ledgerops",
                        "subject-" + ApplicationUserId.newId().value()
                )
        );
    }
}
