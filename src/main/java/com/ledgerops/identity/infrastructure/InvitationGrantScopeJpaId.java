package com.ledgerops.identity.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
class InvitationGrantScopeJpaId implements Serializable {

    @Column(name = "invitation_id")
    private UUID invitationId;

    @Column(name = "assignment_id")
    private UUID assignmentId;

    @Column(name = "merchant_id")
    private UUID merchantId;

    protected InvitationGrantScopeJpaId() {
    }

    InvitationGrantScopeJpaId(UUID invitationId, UUID assignmentId, UUID merchantId) {
        this.invitationId = invitationId;
        this.assignmentId = assignmentId;
        this.merchantId = merchantId;
    }

    UUID invitationId() { return invitationId; }
    UUID assignmentId() { return assignmentId; }
    UUID merchantId() { return merchantId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof InvitationGrantScopeJpaId that)) return false;
        return invitationId.equals(that.invitationId)
                && assignmentId.equals(that.assignmentId)
                && merchantId.equals(that.merchantId);
    }

    @Override
    public int hashCode() {
        int result = invitationId.hashCode();
        result = 31 * result + assignmentId.hashCode();
        return 31 * result + merchantId.hashCode();
    }
}
