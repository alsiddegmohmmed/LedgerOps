package com.ledgerops.identity.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationUserTests {

    @Test
    void createsAnActiveUserWithAnImmutableKeycloakIdentityLink() {
        ApplicationUserId id = ApplicationUserId.newId();
        KeycloakIdentity identity = new KeycloakIdentity(
                "https://keycloak.example/realms/ledgerops",
                "subject-123"
        );

        ApplicationUser user = ApplicationUser.create(id, identity);

        assertThat(user.id()).isEqualTo(id);
        assertThat(user.keycloakIdentity()).isEqualTo(identity);
        assertThat(user.status()).isEqualTo(ApplicationUserStatus.ACTIVE);
    }

    @Test
    void deactivationReturnsADeactivatedCopyWithoutChangingIdentity() {
        ApplicationUser user = ApplicationUser.create(
                ApplicationUserId.newId(),
                new KeycloakIdentity("issuer", "subject")
        );

        ApplicationUser deactivated = user.deactivate();

        assertThat(user.status()).isEqualTo(ApplicationUserStatus.ACTIVE);
        assertThat(deactivated.status()).isEqualTo(ApplicationUserStatus.DEACTIVATED);
        assertThat(deactivated.id()).isEqualTo(user.id());
        assertThat(deactivated.keycloakIdentity()).isEqualTo(user.keycloakIdentity());
    }

    @Test
    void deactivationIsTerminal() {
        ApplicationUser user = ApplicationUser.create(
                ApplicationUserId.newId(),
                new KeycloakIdentity("issuer", "subject")
        ).deactivate();

        assertThatThrownBy(user::deactivate)
                .isInstanceOf(ApplicationUserAlreadyDeactivatedException.class);
    }

    @Test
    void rejectsMissingOrBlankIdentityValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeycloakIdentity(null, "subject"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KeycloakIdentity("issuer", " "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ApplicationUser.create(null, new KeycloakIdentity("issuer", "subject")));
    }
}
