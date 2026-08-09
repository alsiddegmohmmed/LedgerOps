package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.TenantActivationFacts;
import com.ledgerops.identity.domain.TenantActivationReadRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
class TenantActivationReadAdapter implements TenantActivationReadRepository {

    private static final String READINESS_SQL = """
            SELECT
                EXISTS (
                    SELECT 1
                      FROM identity.tenant_memberships membership
                      JOIN identity.application_users application_user
                        ON application_user.id = membership.application_user_id
                      JOIN identity.tenant_role_assignments assignment
                        ON assignment.membership_id = membership.id
                     WHERE membership.tenant_id = ?
                       AND membership.is_initial
                       AND membership.status = 'ACTIVE'
                       AND application_user.status = 'ACTIVE'
                       AND assignment.role = 'TENANT_ADMIN'
                       AND assignment.scope_mode = 'TENANT_WIDE'
                ) AS initial_tenant_admin_active,
                EXISTS (
                    SELECT 1
                      FROM identity.tenant_memberships membership
                      JOIN identity.application_users application_user
                        ON application_user.id = membership.application_user_id
                      JOIN identity.tenant_role_assignments assignment
                        ON assignment.membership_id = membership.id
                      JOIN identity.invitations invitation
                        ON invitation.membership_id = membership.id
                       AND invitation.tenant_id = membership.tenant_id
                     WHERE membership.tenant_id = ?
                       AND membership.is_initial
                       AND membership.status = 'ACTIVE'
                       AND application_user.status = 'ACTIVE'
                       AND assignment.role = 'TENANT_ADMIN'
                       AND assignment.scope_mode = 'TENANT_WIDE'
                       AND invitation.status = 'CONSUMED'
                ) AS onboarding_consistent
            """;

    private final JdbcTemplate jdbc;

    TenantActivationReadAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TenantActivationFacts assess(UUID tenantId) {
        return jdbc.queryForObject(
                READINESS_SQL,
                (resultSet, rowNumber) -> new TenantActivationFacts(
                        resultSet.getBoolean("initial_tenant_admin_active"),
                        resultSet.getBoolean("onboarding_consistent")
                ),
                tenantId,
                tenantId
        );
    }
}
