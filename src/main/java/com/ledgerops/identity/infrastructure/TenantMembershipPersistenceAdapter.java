package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.MerchantScope;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.TenantMembership;
import com.ledgerops.identity.domain.TenantMembershipId;
import com.ledgerops.identity.domain.TenantMembershipRepository;
import com.ledgerops.identity.domain.TenantMembershipStatus;
import com.ledgerops.identity.domain.TenantRole;
import com.ledgerops.identity.domain.TenantRoleAssignment;
import com.ledgerops.identity.domain.TenantRoleAssignmentId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
class TenantMembershipPersistenceAdapter implements TenantMembershipRepository {

    private final SpringDataTenantMembershipRepository repository;
    private final Clock clock;

    TenantMembershipPersistenceAdapter(
            SpringDataTenantMembershipRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TenantMembership save(TenantMembership membership) {
        Instant now = clock.instant();
        TenantMembershipJpaEntity entity = repository.findAggregateById(membership.id().value())
                .map(existing -> update(existing, membership, now))
                .orElseGet(() -> create(membership, now));
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantMembership> findById(TenantMembershipId membershipId) {
        return repository.findAggregateById(membershipId.value()).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantMembership> findActiveByApplicationUserAndTenant(
            ApplicationUserId applicationUserId,
            UUID tenantId
    ) {
        return repository.findActiveByApplicationUserIdAndTenantId(
                        applicationUserId.value(), tenantId
                )
                .map(this::toDomain);
    }

    private TenantMembershipJpaEntity create(TenantMembership membership, Instant now) {
        TenantMembershipJpaEntity entity = new TenantMembershipJpaEntity(
                membership.id().value(),
                membership.applicationUserId() == null
                        ? null
                        : membership.applicationUserId().value(),
                membership.tenantId(),
                membership.status().name(),
                membership.initial(),
                now,
                now,
                new LinkedHashSet<>()
        );
        replaceRoleAssignments(entity, membership);
        return entity;
    }

    private TenantMembershipJpaEntity update(
            TenantMembershipJpaEntity entity,
            TenantMembership membership,
            Instant now
    ) {
        if (!entity.tenantId().equals(membership.tenantId())) {
            throw new IllegalArgumentException("Membership Tenant ownership is immutable");
        }
        if (entity.initial() != membership.initial()) {
            throw new IllegalArgumentException("Membership initial identity is immutable");
        }
        synchronizeRoleAssignments(entity, membership);
        entity.update(
                membership.applicationUserId() == null
                        ? null
                        : membership.applicationUserId().value(),
                membership.status().name(),
                now
        );
        return entity;
    }

    private void synchronizeRoleAssignments(
            TenantMembershipJpaEntity entity,
            TenantMembership membership
    ) {
        if (entity.status().equals(TenantMembershipStatus.INVITED.name())
                && membership.status() == TenantMembershipStatus.ACTIVE) {
            if (!entity.roleAssignments().isEmpty()) {
                throw new IllegalArgumentException(
                        "Invited membership already has active role assignments");
            }
            replaceRoleAssignments(entity, membership);
            return;
        }

        Map<UUID, TenantRoleAssignmentJpaEntity> persisted = entity.roleAssignments().stream()
                .collect(Collectors.toMap(
                        TenantRoleAssignmentJpaEntity::id,
                        assignment -> assignment
                ));
        if (persisted.size() != membership.roleAssignments().size()
                || membership.roleAssignments().stream().anyMatch(assignment -> {
                    TenantRoleAssignmentJpaEntity row = persisted.get(assignment.id().value());
                    if (row == null
                            || !row.role().equals(assignment.role().name())
                            || !row.scopeMode().equals(assignment.scopeMode().name())) {
                        return true;
                    }
                    Set<UUID> merchantIds = assignment.scopeMode() == ScopeMode.MERCHANT_SET
                            ? assignment.merchantScope().merchantIds()
                            : Set.of();
                    return !row.merchantIds().equals(merchantIds);
                })) {
            throw new IllegalArgumentException(
                    "Role assignment changes require explicit assignment replacement");
        }
    }

    private void replaceRoleAssignments(
            TenantMembershipJpaEntity entity,
            TenantMembership membership
    ) {
        if (membership.status() == TenantMembershipStatus.INVITED) {
            if (!membership.roleAssignments().isEmpty()) {
                throw new IllegalArgumentException(
                        "Invitation role proposals must be persisted through invitation grants");
            }
            return;
        }
        for (TenantRoleAssignment assignment : membership.roleAssignments()) {
            Set<UUID> merchantIds = assignment.scopeMode() == ScopeMode.MERCHANT_SET
                    ? assignment.merchantScope().merchantIds()
                    : Set.of();
            entity.roleAssignments().add(new TenantRoleAssignmentJpaEntity(
                    assignment.id().value(),
                    entity,
                    assignment.role().name(),
                    assignment.scopeMode().name(),
                    merchantIds
            ));
        }
    }

    private TenantMembership toDomain(TenantMembershipJpaEntity entity) {
        Set<TenantRoleAssignment> assignments = entity.roleAssignments().stream()
                .map(assignment -> toDomain(entity.tenantId(), assignment))
                .collect(Collectors.toUnmodifiableSet());
        return TenantMembership.reconstitute(
                new TenantMembershipId(entity.id()),
                entity.tenantId(),
                entity.applicationUserId() == null
                        ? null
                        : new ApplicationUserId(entity.applicationUserId()),
                com.ledgerops.identity.domain.TenantMembershipStatus.valueOf(entity.status()),
                assignments,
                entity.version(),
                entity.initial()
        );
    }

    private TenantRoleAssignment toDomain(
            UUID tenantId,
            TenantRoleAssignmentJpaEntity entity
    ) {
        TenantRole role = TenantRole.valueOf(entity.role());
        ScopeMode scopeMode = ScopeMode.valueOf(entity.scopeMode());
        if (scopeMode == ScopeMode.TENANT_WIDE) {
            return TenantRoleAssignment.tenantWide(
                    new TenantRoleAssignmentId(entity.id()), tenantId, role
            );
        }
        Map<UUID, UUID> ownership = new LinkedHashMap<>();
        for (UUID merchantId : entity.merchantIds()) {
            ownership.put(merchantId, tenantId);
        }
        MerchantScope scope = MerchantScope.validated(
                tenantId, new LinkedHashSet<>(entity.merchantIds()), ownership
        );
        return TenantRoleAssignment.merchantScoped(
                new TenantRoleAssignmentId(entity.id()), tenantId, role, scope
        );
    }
}
