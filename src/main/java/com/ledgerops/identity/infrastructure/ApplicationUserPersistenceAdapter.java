package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.ApplicationUser;
import com.ledgerops.identity.domain.ApplicationUserIdentityConflictException;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.ApplicationUserRepository;
import com.ledgerops.identity.domain.ApplicationUserStatus;
import com.ledgerops.identity.domain.KeycloakIdentity;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Repository
class ApplicationUserPersistenceAdapter implements ApplicationUserRepository {

    private final SpringDataApplicationUserRepository repository;
    private final Clock clock;

    ApplicationUserPersistenceAdapter(
            SpringDataApplicationUserRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ApplicationUser save(ApplicationUser applicationUser) {
        Instant now = clock.instant();
        ApplicationUserJpaEntity entity = repository.findById(applicationUser.id().value())
                .map(existing -> update(existing, applicationUser, now))
                .orElseGet(() -> create(applicationUser, now));

        try {
            return toDomain(repository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException exception) {
            if (causedByIdentityConstraint(exception)) {
                throw new ApplicationUserIdentityConflictException(
                        applicationUser.keycloakIdentity()
                );
            }
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApplicationUser> findById(ApplicationUserId id) {
        return repository.findById(id.value()).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApplicationUser> findByKeycloakIdentity(KeycloakIdentity keycloakIdentity) {
        return repository.findByIssuerAndSubject(
                keycloakIdentity.issuer(),
                keycloakIdentity.subject()
        ).map(this::toDomain);
    }

    private ApplicationUserJpaEntity create(ApplicationUser applicationUser, Instant now) {
        return new ApplicationUserJpaEntity(
                applicationUser.id().value(),
                applicationUser.keycloakIdentity().issuer(),
                applicationUser.keycloakIdentity().subject(),
                applicationUser.status().name(),
                now,
                now
        );
    }

    private ApplicationUserJpaEntity update(
            ApplicationUserJpaEntity entity,
            ApplicationUser applicationUser,
            Instant now
    ) {
        if (!entity.issuer().equals(applicationUser.keycloakIdentity().issuer())
                || !entity.subject().equals(applicationUser.keycloakIdentity().subject())) {
            throw new ApplicationUserIdentityConflictException(
                    applicationUser.keycloakIdentity()
            );
        }
        entity.updateStatus(applicationUser.status().name(), now);
        return entity;
    }

    private ApplicationUser toDomain(ApplicationUserJpaEntity entity) {
        ApplicationUser user = ApplicationUser.create(
                new ApplicationUserId(entity.id()),
                new KeycloakIdentity(entity.issuer(), entity.subject())
        );
        return entity.status().equals(ApplicationUserStatus.DEACTIVATED.name())
                ? user.deactivate()
                : user;
    }

    private boolean causedByIdentityConstraint(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                return "uk_application_users_issuer_subject".equals(
                        violation.getConstraintName()
                );
            }
            cause = cause.getCause();
        }
        return false;
    }
}
