package com.ledgerops.identity.application;

import com.ledgerops.identity.api.NotificationCapability;
import com.ledgerops.identity.api.NotificationRecipient;
import com.ledgerops.identity.domain.ApplicationUser;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.ApplicationUserRepository;
import com.ledgerops.identity.domain.KeycloakIdentity;
import com.ledgerops.identity.domain.TenantMembership;
import com.ledgerops.identity.domain.TenantMembershipId;
import com.ledgerops.identity.domain.TenantMembershipRepository;
import com.ledgerops.identity.domain.TenantMembershipStatus;
import com.ledgerops.identity.domain.TenantRole;
import com.ledgerops.identity.domain.TenantRoleAssignment;
import com.ledgerops.identity.domain.TenantRoleAssignmentId;
import com.ledgerops.identity.domain.MerchantScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRecipientQueryServiceTests {

    @Test
    void selectsOnlyActiveUsersWithTenantWideCapability() {
        UUID tenantId = UUID.randomUUID();
        ApplicationUser user = user();
        TenantMembership membership = TenantMembership.active(
                TenantMembershipId.newId(), tenantId, user.id(),
                Set.of(TenantRoleAssignment.tenantWide(
                        TenantRoleAssignmentId.newId(), tenantId, TenantRole.TENANT_ADMIN)));
        NotificationRecipientQueryService service = service(List.of(membership), user);

        List<NotificationRecipient> recipients = service.findRecipients(
                tenantId, UUID.randomUUID(), NotificationCapability.RISK_READ);

        assertThat(recipients).singleElement().satisfies(recipient -> {
            assertThat(recipient.applicationUserId()).isEqualTo(user.id().value());
            assertThat(recipient.tenantWide()).isTrue();
            assertThat(recipient.merchantIds()).isEmpty();
        });
    }

    @Test
    void restrictsMerchantScopedCapabilityToTheAssignedMerchant() {
        UUID tenantId = UUID.randomUUID();
        UUID allowedMerchant = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        ApplicationUser user = user();
        TenantMembership membership = TenantMembership.active(
                TenantMembershipId.newId(), tenantId, user.id(),
                Set.of(TenantRoleAssignment.merchantScoped(
                        TenantRoleAssignmentId.newId(), tenantId, TenantRole.MERCHANT_ADMIN,
                        MerchantScope.validated(tenantId, Set.of(allowedMerchant),
                                Map.of(allowedMerchant, tenantId)))));
        NotificationRecipientQueryService service = service(List.of(membership), user);

        assertThat(service.findRecipients(
                tenantId, allowedMerchant, NotificationCapability.RISK_READ))
                .extracting(NotificationRecipient::applicationUserId)
                .containsExactly(user.id().value());
        assertThat(service.findRecipients(
                tenantId, otherMerchant, NotificationCapability.RISK_READ))
                .isEmpty();
    }

    @Test
    void excludesSuspendedMembershipsAndDeactivatedUsers() {
        UUID tenantId = UUID.randomUUID();
        ApplicationUser activeUser = user();
        ApplicationUser deactivatedUser = user();
        TenantRoleAssignment assignment = TenantRoleAssignment.tenantWide(
                TenantRoleAssignmentId.newId(), tenantId, TenantRole.TENANT_ADMIN);
        TenantMembership suspended = TenantMembership.reconstitute(
                TenantMembershipId.newId(), tenantId, activeUser.id(),
                TenantMembershipStatus.SUSPENDED, Set.of(assignment));
        TenantMembership activeForDeactivatedUser = TenantMembership.active(
                TenantMembershipId.newId(), tenantId, deactivatedUser.id(), Set.of(assignment));
        NotificationRecipientQueryService service = service(
                List.of(suspended, activeForDeactivatedUser), activeUser, deactivatedUser.deactivate());

        assertThat(service.findRecipients(
                tenantId, null, NotificationCapability.NOTIFICATION_READ)).isEmpty();
    }

    private NotificationRecipientQueryService service(
            List<TenantMembership> memberships,
            ApplicationUser... users
    ) {
        List<TenantMembership> storedMemberships = List.copyOf(memberships);
        List<ApplicationUser> storedUsers = List.of(users);
        TenantMembershipRepository membershipRepository = new TenantMembershipRepository() {
            @Override public TenantMembership save(TenantMembership membership) { return membership; }
            @Override public Optional<TenantMembership> findById(TenantMembershipId id) {
                return storedMemberships.stream().filter(value -> value.id().equals(id)).findFirst();
            }
            @Override public Optional<TenantMembership> findByIdForUpdate(TenantMembershipId id) {
                return findById(id);
            }
            @Override public List<TenantMembership> findAllByTenantId(UUID tenantId) {
                return storedMemberships.stream()
                        .filter(value -> value.tenantId().equals(tenantId)).toList();
            }
            @Override public Optional<TenantMembership> findActiveByApplicationUserAndTenant(
                    ApplicationUserId applicationUserId, UUID tenantId) {
                return storedMemberships.stream()
                        .filter(value -> value.tenantId().equals(tenantId)
                                && value.applicationUserId() != null
                                && value.applicationUserId().equals(applicationUserId)
                                && value.status() == TenantMembershipStatus.ACTIVE)
                        .findFirst();
            }
        };
        ApplicationUserRepository userRepository = new ApplicationUserRepository() {
            @Override public ApplicationUser save(ApplicationUser user) { return user; }
            @Override public Optional<ApplicationUser> findById(ApplicationUserId id) {
                return storedUsers.stream().filter(value -> value.id().equals(id)).findFirst();
            }
            @Override public Optional<ApplicationUser> findByKeycloakIdentity(KeycloakIdentity identity) {
                return storedUsers.stream().filter(value -> value.keycloakIdentity().equals(identity)).findFirst();
            }
        };
        return new NotificationRecipientQueryService(membershipRepository, userRepository);
    }

    private ApplicationUser user() {
        return ApplicationUser.create(
                ApplicationUserId.newId(),
                new KeycloakIdentity("issuer", UUID.randomUUID().toString()));
    }
}
