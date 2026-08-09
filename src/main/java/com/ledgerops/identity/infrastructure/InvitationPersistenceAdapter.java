package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.Invitation;
import com.ledgerops.identity.domain.InvitationId;
import com.ledgerops.identity.domain.InvitationRepository;
import com.ledgerops.identity.domain.InvitationStatus;
import com.ledgerops.identity.domain.InvitationTokenHash;
import com.ledgerops.identity.domain.MerchantScope;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.TenantMembershipId;
import com.ledgerops.identity.domain.TenantRoleAssignment;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
class InvitationPersistenceAdapter implements InvitationRepository {

    private final SpringDataInvitationRepository invitations;
    private final SpringDataInvitationGrantRepository grants;
    private final SpringDataInvitationGrantScopeRepository scopes;
    private final Clock clock;

    InvitationPersistenceAdapter(
            SpringDataInvitationRepository invitations,
            SpringDataInvitationGrantRepository grants,
            SpringDataInvitationGrantScopeRepository scopes,
            Clock clock
    ) {
        this.invitations = invitations;
        this.grants = grants;
        this.scopes = scopes;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Invitation save(Invitation invitation, TenantMembershipId membershipId) {
        Optional<InvitationJpaEntity> existing = invitations.findById(invitation.id().value());
        if (existing.isPresent()) {
            InvitationJpaEntity entity = existing.get();
            if (!entity.membershipId().equals(membershipId.value())) {
                throw new IllegalArgumentException("Invitation membership identity is immutable");
            }
            assertGrantIntentMatches(invitation, loadGrantRows(invitation.id().value()));
            entity.updateLifecycle(invitation.status(), clock.instant());
            return toDomain(invitations.saveAndFlush(entity));
        }

        InvitationJpaEntity entity = new InvitationJpaEntity(
                invitation.id().value(),
                invitation.tenantId(),
                membershipId.value(),
                invitation.intendedEmail(),
                invitation.tokenHash().value(),
                invitation.status(),
                invitation.createdAt(),
                invitation.expiresAt()
        );
        invitations.saveAndFlush(entity);

        List<InvitationGrantJpaEntity> grantRows = invitation.proposedAssignments().stream()
                .map(assignment -> new InvitationGrantJpaEntity(
                        invitation.id().value(),
                        assignment.id().value(),
                        invitation.tenantId(),
                        assignment.role(),
                        assignment.scopeMode()
                ))
                .toList();
        grants.saveAllAndFlush(grantRows);

        List<InvitationGrantScopeJpaEntity> scopeRows = invitation.proposedAssignments().stream()
                .filter(assignment -> assignment.scopeMode() == ScopeMode.MERCHANT_SET)
                .flatMap(assignment -> assignment.merchantScope().merchantIds().stream()
                        .map(merchantId -> new InvitationGrantScopeJpaEntity(
                                invitation.id().value(),
                                assignment.id().value(),
                                invitation.tenantId(),
                                merchantId
                        )))
                .toList();
        if (!scopeRows.isEmpty()) {
            scopes.saveAllAndFlush(scopeRows);
        }

        return toDomain(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findById(InvitationId invitationId) {
        return invitations.findById(invitationId.value()).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findByMembershipId(TenantMembershipId membershipId) {
        return invitations.findByMembershipId(membershipId.value()).map(this::toDomain);
    }

    @Override
    @Transactional
    public Optional<Invitation> findByMembershipIdForUpdate(TenantMembershipId membershipId) {
        return invitations.findByMembershipIdForUpdate(membershipId.value()).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantMembershipId> findMembershipId(InvitationId invitationId) {
        return invitations.findById(invitationId.value())
                .map(entity -> new TenantMembershipId(entity.membershipId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findPendingByTokenHash(InvitationTokenHash tokenHash) {
        return invitations.findPendingByTokenHash(tokenHash.value()).map(this::toDomain);
    }

    @Override
    @Transactional
    public Optional<Invitation> findPendingByTokenHashForUpdate(InvitationTokenHash tokenHash) {
        return invitations.findPendingByTokenHashForUpdate(tokenHash.value()).map(this::toDomain);
    }

    private Invitation toDomain(InvitationJpaEntity entity) {
        List<InvitationGrantJpaEntity> grantRows = loadGrantRows(entity.id());
        List<InvitationGrantScopeJpaEntity> scopeRows = scopes.findAllByIdInvitationId(entity.id());
        Map<UUID, Set<UUID>> merchantIdsByAssignment = scopeRows.stream()
                .collect(Collectors.groupingBy(
                        InvitationGrantScopeJpaEntity::assignmentId,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                InvitationGrantScopeJpaEntity::merchantId,
                                Collectors.toCollection(LinkedHashSet::new)
                        )
                ));

        Set<TenantRoleAssignment> assignments = grantRows.stream()
                .map(grant -> toDomain(entity.tenantId(), grant, merchantIdsByAssignment))
                .collect(Collectors.toUnmodifiableSet());
        return Invitation.reconstitute(
                new InvitationId(entity.id()),
                entity.tenantId(),
                entity.intendedEmail(),
                new InvitationTokenHash(entity.tokenHash()),
                assignments,
                entity.createdAt(),
                entity.status()
        );
    }

    private TenantRoleAssignment toDomain(
            UUID tenantId,
            InvitationGrantJpaEntity grant,
            Map<UUID, Set<UUID>> merchantIdsByAssignment
    ) {
        if (grant.scopeMode() == ScopeMode.TENANT_WIDE) {
            return TenantRoleAssignment.tenantWide(
                    new com.ledgerops.identity.domain.TenantRoleAssignmentId(grant.assignmentId()),
                    tenantId,
                    grant.role()
            );
        }
        Set<UUID> merchantIds = merchantIdsByAssignment.getOrDefault(
                grant.assignmentId(), Set.of()
        );
        Map<UUID, UUID> ownership = merchantIds.stream()
                .collect(Collectors.toMap(Function.identity(), ignored -> tenantId));
        MerchantScope scope = MerchantScope.validated(tenantId, merchantIds, ownership);
        return TenantRoleAssignment.merchantScoped(
                new com.ledgerops.identity.domain.TenantRoleAssignmentId(grant.assignmentId()),
                tenantId,
                grant.role(),
                scope
        );
    }

    private List<InvitationGrantJpaEntity> loadGrantRows(UUID invitationId) {
        return grants.findAllByIdInvitationId(invitationId);
    }

    private void assertGrantIntentMatches(
            Invitation invitation,
            List<InvitationGrantJpaEntity> persistedGrants
    ) {
        Map<UUID, TenantRoleAssignment> expected = invitation.proposedAssignments().stream()
                .collect(Collectors.toMap(
                        assignment -> assignment.id().value(),
                        Function.identity()
                ));
        if (expected.size() != persistedGrants.size()) {
            throw new IllegalArgumentException("Invitation grant intent is immutable");
        }
        for (InvitationGrantJpaEntity persisted : persistedGrants) {
            TenantRoleAssignment assignment = expected.get(persisted.assignmentId());
            if (assignment == null
                    || assignment.role() != persisted.role()
                    || assignment.scopeMode() != persisted.scopeMode()) {
                throw new IllegalArgumentException("Invitation grant intent is immutable");
            }
        }
        Map<UUID, Set<UUID>> persistedScopes = scopes.findAllByIdInvitationId(invitation.id().value())
                .stream()
                .collect(Collectors.groupingBy(
                        InvitationGrantScopeJpaEntity::assignmentId,
                        Collectors.mapping(
                                InvitationGrantScopeJpaEntity::merchantId,
                                Collectors.toUnmodifiableSet()
                        )
                ));
        for (TenantRoleAssignment assignment : expected.values()) {
            Set<UUID> expectedMerchants = assignment.scopeMode() == ScopeMode.MERCHANT_SET
                    ? assignment.merchantScope().merchantIds()
                    : Set.of();
            if (!expectedMerchants.equals(
                    persistedScopes.getOrDefault(assignment.id().value(), Set.of()))) {
                throw new IllegalArgumentException("Invitation Merchant scope intent is immutable");
            }
        }
    }
}
