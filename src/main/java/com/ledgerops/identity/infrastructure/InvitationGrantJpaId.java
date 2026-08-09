package com.ledgerops.identity.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
class InvitationGrantJpaId implements Serializable {

    @Column(name = "invitation_id")
    private UUID invitationId;

    @Column(name = "assignment_id")
    private UUID assignmentId;

    protected InvitationGrantJpaId() {
    }

    InvitationGrantJpaId(UUID invitationId, UUID assignmentId) {
        this.invitationId = invitationId;
        this.assignmentId = assignmentId;
    }

    UUID invitationId() { return invitationId; }
    UUID assignmentId() { return assignmentId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof InvitationGrantJpaId that)) return false;
        return invitationId.equals(that.invitationId)
                && assignmentId.equals(that.assignmentId);
    }

    @Override
    public int hashCode() {
        return 31 * invitationId.hashCode() + assignmentId.hashCode();
    }
}
