ALTER TABLE messaging.outbox
    DROP CONSTRAINT ck_outbox_producer;

ALTER TABLE messaging.outbox
    ADD CONSTRAINT ck_outbox_producer
        CHECK (producer_name IN (
            'payment', 'provider', 'tenancy', 'merchant', 'identity',
            'risk', 'reconciliation', 'casework'
        ));

CREATE SCHEMA casework;

CREATE TABLE risk.risk_reviews (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    evaluation_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    assigned_analyst_id UUID,
    priority INTEGER NOT NULL,
    sla_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    decision VARCHAR(16),
    decision_reason TEXT,
    case_id UUID,
    decided_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_risk_review_tenant_payment UNIQUE (tenant_id, payment_id),
    CONSTRAINT uk_risk_review_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_risk_review_tenant_evaluation UNIQUE (tenant_id, evaluation_id),
    CONSTRAINT ck_risk_review_status CHECK (status IN ('UNASSIGNED', 'ASSIGNED', 'DECIDED', 'ESCALATED')),
    CONSTRAINT ck_risk_review_priority CHECK (priority >= 0),
    CONSTRAINT ck_risk_review_sla_version CHECK (sla_version >= 1),
    CONSTRAINT ck_risk_review_due_after_creation CHECK (due_at >= created_at),
    CONSTRAINT ck_risk_review_decision CHECK (decision IN ('APPROVE', 'REJECT', 'ESCALATE') OR decision IS NULL),
    CONSTRAINT ck_risk_review_state_evidence CHECK (
        (status = 'UNASSIGNED' AND assigned_analyst_id IS NULL AND decision IS NULL AND case_id IS NULL AND decided_at IS NULL)
        OR (status = 'ASSIGNED' AND assigned_analyst_id IS NOT NULL AND decision IS NULL AND case_id IS NULL AND decided_at IS NULL)
        OR (status = 'DECIDED' AND assigned_analyst_id IS NOT NULL AND decision IN ('APPROVE', 'REJECT')
            AND decision_reason IS NOT NULL AND length(trim(decision_reason)) > 0 AND case_id IS NULL AND decided_at IS NOT NULL)
        OR (status = 'ESCALATED' AND assigned_analyst_id IS NOT NULL AND decision = 'ESCALATE'
            AND decision_reason IS NOT NULL AND length(trim(decision_reason)) > 0 AND case_id IS NOT NULL AND decided_at IS NOT NULL)
    ),
    CONSTRAINT ck_risk_review_version CHECK (version >= 0)
);

CREATE INDEX ix_risk_reviews_queue
    ON risk.risk_reviews (tenant_id, status, due_at, priority DESC, id);

INSERT INTO risk.risk_reviews (
    id, tenant_id, payment_id, evaluation_id, status, assigned_analyst_id,
    priority, sla_version, created_at, due_at, decision, decision_reason,
    case_id, decided_at, version
)
SELECT gen_random_uuid(), evaluation.tenant_id, evaluation.payment_id, evaluation.id,
       'UNASSIGNED', NULL, evaluation.final_score, 1, evaluation.evaluated_at,
       evaluation.evaluated_at + INTERVAL '24 hours', NULL, NULL, NULL, NULL, 0
  FROM risk.risk_evaluations evaluation
 WHERE evaluation.decision = 'MANUAL_REVIEW'
ON CONFLICT (tenant_id, payment_id) DO NOTHING;

CREATE TABLE casework.cases (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    source_category VARCHAR(32) NOT NULL,
    source_id UUID NOT NULL,
    related_payment_id UUID,
    severity VARCHAR(16) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL,
    owner_id UUID,
    resolution VARCHAR(48),
    resolution_note TEXT,
    corrective_action_required BOOLEAN NOT NULL DEFAULT FALSE,
    corrective_action_completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_casework_source UNIQUE (tenant_id, source_category, source_id),
    CONSTRAINT uk_casework_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_casework_source_category CHECK (source_category IN ('RISK_REVIEW', 'RECONCILIATION_DISCREPANCY')),
    CONSTRAINT ck_casework_severity CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_casework_status CHECK (status IN ('OPEN', 'INVESTIGATING', 'AWAITING_INFORMATION', 'RESOLVED', 'CLOSED', 'REOPENED')),
    CONSTRAINT ck_casework_resolution CHECK (resolution IN (
        'RISK_APPROVE', 'RISK_REJECT', 'PROVIDER_ERROR', 'INTERNAL_PROCESSING_ERROR',
        'DUPLICATE_EXTERNAL_RECORD', 'EXPECTED_TIMING_DIFFERENCE', 'APPROVED_CORRECTION', 'FALSE_POSITIVE'
    ) OR resolution IS NULL),
    CONSTRAINT ck_casework_resolution_evidence CHECK (
        status NOT IN ('RESOLVED', 'CLOSED')
        OR (resolution IS NOT NULL AND resolution_note IS NOT NULL AND length(trim(resolution_note)) > 0)
    ),
    CONSTRAINT ck_casework_correction_effect CHECK (
        NOT corrective_action_required OR corrective_action_completed OR status NOT IN ('CLOSED', 'RESOLVED')
    )
);

CREATE INDEX ix_casework_queue
    ON casework.cases (tenant_id, status, due_at, severity, owner_id);

CREATE TABLE casework.case_history (
    case_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(24),
    to_status VARCHAR(24),
    actor_id UUID NOT NULL,
    reason TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (case_id, sequence),
    CONSTRAINT fk_case_history_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES casework.cases (tenant_id, id),
    CONSTRAINT ck_case_history_sequence CHECK (sequence > 0)
);

CREATE TABLE casework.case_notes (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    case_id UUID NOT NULL,
    author_id UUID NOT NULL,
    note TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_case_note_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES casework.cases (tenant_id, id),
    CONSTRAINT ck_case_note_not_blank CHECK (length(trim(note)) > 0)
);

CREATE FUNCTION casework.reject_case_history_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Case history is append-only';
END;
$$;

CREATE TRIGGER case_history_append_only
BEFORE UPDATE OR DELETE ON casework.case_history
FOR EACH ROW EXECUTE FUNCTION casework.reject_case_history_mutation();

CREATE FUNCTION casework.reject_case_note_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Case notes are append-only';
END;
$$;

CREATE TRIGGER case_notes_append_only
BEFORE UPDATE OR DELETE ON casework.case_notes
FOR EACH ROW EXECUTE FUNCTION casework.reject_case_note_mutation();
