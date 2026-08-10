ALTER TABLE provider.work
    ADD COLUMN operation_type VARCHAR(16) NOT NULL DEFAULT 'PAYMENT',
    ADD COLUMN operation_id UUID;

CREATE FUNCTION provider.populate_work_operation_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.operation_type IS NULL THEN
        NEW.operation_type := 'PAYMENT';
    END IF;
    IF NEW.operation_id IS NULL AND NEW.operation_type = 'PAYMENT' THEN
        NEW.operation_id := NEW.payment_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER provider_work_operation_identity_before_insert
BEFORE INSERT ON provider.work
FOR EACH ROW EXECUTE FUNCTION provider.populate_work_operation_identity();

UPDATE provider.work
   SET operation_id = payment_id
 WHERE operation_id IS NULL;

ALTER TABLE provider.work
    ALTER COLUMN operation_id SET NOT NULL,
    DROP CONSTRAINT ck_provider_work_key,
    ADD CONSTRAINT ck_provider_work_operation_type CHECK (
        operation_type IN ('PAYMENT', 'REVERSAL')
    ),
    ADD CONSTRAINT ck_provider_work_operation_identity CHECK (
        (operation_type = 'PAYMENT' AND operation_id = payment_id)
        OR operation_type = 'REVERSAL'
    ),
    ADD CONSTRAINT ck_provider_work_key CHECK (
        provider_idempotency_key = CASE operation_type
            WHEN 'PAYMENT' THEN 'payment:' || lower(operation_id::text)
            WHEN 'REVERSAL' THEN 'reversal:' || lower(operation_id::text)
        END
    );

CREATE INDEX ix_provider_work_operation
    ON provider.work (tenant_id, operation_type, operation_id);

CREATE OR REPLACE FUNCTION provider.reject_work_business_content_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.payment_id IS DISTINCT FROM OLD.payment_id
       OR NEW.operation_type IS DISTINCT FROM OLD.operation_type
       OR NEW.operation_id IS DISTINCT FROM OLD.operation_id
       OR NEW.work_type IS DISTINCT FROM OLD.work_type
       OR NEW.provider_id IS DISTINCT FROM OLD.provider_id
       OR NEW.provider_idempotency_key IS DISTINCT FROM OLD.provider_idempotency_key
       OR NEW.request_intent_hash IS DISTINCT FROM OLD.request_intent_hash
       OR NEW.command_payload IS DISTINCT FROM OLD.command_payload
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.causation_id IS DISTINCT FROM OLD.causation_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Provider work business identity and content are immutable';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE provider.interactions
    ADD COLUMN operation_type VARCHAR(16),
    ADD COLUMN operation_id UUID;

UPDATE provider.interactions interaction
   SET operation_type = work.operation_type,
       operation_id = work.operation_id
  FROM provider.work work
 WHERE work.id = interaction.work_id
   AND work.tenant_id = interaction.tenant_id;

CREATE FUNCTION provider.populate_interaction_operation_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    work_operation_type VARCHAR(16);
    work_operation_id UUID;
BEGIN
    SELECT operation_type, operation_id
      INTO work_operation_type, work_operation_id
      FROM provider.work
     WHERE id = NEW.work_id AND tenant_id = NEW.tenant_id;

    IF work_operation_type IS NOT NULL THEN
        IF NEW.operation_type IS NULL THEN
            NEW.operation_type := work_operation_type;
        END IF;
        IF NEW.operation_id IS NULL THEN
            NEW.operation_id := work_operation_id;
        END IF;
        IF NEW.operation_type IS DISTINCT FROM work_operation_type
           OR NEW.operation_id IS DISTINCT FROM work_operation_id THEN
            RAISE EXCEPTION 'Provider interaction operation identity does not match its work item';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER provider_interaction_operation_identity_before_insert
BEFORE INSERT ON provider.interactions
FOR EACH ROW EXECUTE FUNCTION provider.populate_interaction_operation_identity();

ALTER TABLE provider.interactions
    ALTER COLUMN operation_type SET NOT NULL,
    ALTER COLUMN operation_id SET NOT NULL,
    ADD CONSTRAINT ck_provider_interaction_operation_type CHECK (
        operation_type IN ('PAYMENT', 'REVERSAL')
    ),
    ADD CONSTRAINT ck_provider_interaction_operation_identity CHECK (
        (operation_type = 'PAYMENT' AND operation_id = payment_id)
        OR operation_type = 'REVERSAL'
    );

ALTER TABLE provider.results
    ADD COLUMN operation_type VARCHAR(16),
    ADD COLUMN operation_id UUID;

UPDATE provider.results result
   SET operation_type = work.operation_type,
       operation_id = work.operation_id
  FROM provider.work work
 WHERE work.id = result.work_id
   AND work.tenant_id = result.tenant_id;

CREATE FUNCTION provider.populate_result_operation_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    work_operation_type VARCHAR(16);
    work_operation_id UUID;
BEGIN
    SELECT operation_type, operation_id
      INTO work_operation_type, work_operation_id
      FROM provider.work
     WHERE id = NEW.work_id AND tenant_id = NEW.tenant_id;

    IF work_operation_type IS NOT NULL THEN
        IF NEW.operation_type IS NULL THEN
            NEW.operation_type := work_operation_type;
        END IF;
        IF NEW.operation_id IS NULL THEN
            NEW.operation_id := work_operation_id;
        END IF;
        IF NEW.operation_type IS DISTINCT FROM work_operation_type
           OR NEW.operation_id IS DISTINCT FROM work_operation_id THEN
            RAISE EXCEPTION 'Provider result operation identity does not match its work item';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER provider_result_operation_identity_before_insert
BEFORE INSERT ON provider.results
FOR EACH ROW EXECUTE FUNCTION provider.populate_result_operation_identity();

ALTER TABLE provider.results
    ALTER COLUMN operation_type SET NOT NULL,
    ALTER COLUMN operation_id SET NOT NULL,
    ADD CONSTRAINT ck_provider_result_operation_type CHECK (
        operation_type IN ('PAYMENT', 'REVERSAL')
    ),
    ADD CONSTRAINT ck_provider_result_operation_identity CHECK (
        (operation_type = 'PAYMENT' AND operation_id = payment_id)
        OR operation_type = 'REVERSAL'
    ),
    ADD CONSTRAINT ck_provider_result_key CHECK (
        provider_idempotency_key = CASE operation_type
            WHEN 'PAYMENT' THEN 'payment:' || lower(operation_id::text)
            WHEN 'REVERSAL' THEN 'reversal:' || lower(operation_id::text)
        END
    );
