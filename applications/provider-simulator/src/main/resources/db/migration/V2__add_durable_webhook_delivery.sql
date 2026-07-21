ALTER TABLE simulator.provider_transactions
    DROP CONSTRAINT ck_simulator_scenario,
    ADD CONSTRAINT ck_simulator_scenario CHECK (
        scenario IN ('SUCCESS', 'DECLINE', 'ACCEPTED', 'PENDING',
                     'TEMPORARY_FAILURE', 'PERMANENT_FAILURE',
                     'TIMEOUT', 'SLOW_RESPONSE', 'TIMEOUT_THEN_SUCCESS',
                     'DELAYED_WEBHOOK', 'DUPLICATE_WEBHOOK', 'MISSING_WEBHOOK',
                     'OUT_OF_ORDER_WEBHOOK', 'INVALID_SIGNATURE',
                     'CONFLICTING_RESULT')
    );

ALTER TABLE simulator.scenario_overrides
    DROP CONSTRAINT ck_simulator_override_scenario,
    ADD CONSTRAINT ck_simulator_override_scenario CHECK (
        scenario IN ('SUCCESS', 'DECLINE', 'ACCEPTED', 'PENDING',
                     'TEMPORARY_FAILURE', 'PERMANENT_FAILURE',
                     'TIMEOUT', 'SLOW_RESPONSE', 'TIMEOUT_THEN_SUCCESS',
                     'DELAYED_WEBHOOK', 'DUPLICATE_WEBHOOK', 'MISSING_WEBHOOK',
                     'OUT_OF_ORDER_WEBHOOK', 'INVALID_SIGNATURE',
                     'CONFLICTING_RESULT')
    );

CREATE TABLE simulator.webhook_deliveries (
    delivery_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    delivery_sequence INTEGER NOT NULL,
    provider_event_id UUID NOT NULL UNIQUE,
    payload TEXT NOT NULL,
    signature_mode VARCHAR(16) NOT NULL,
    repeat_remaining INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner TEXT,
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    last_http_status INTEGER,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    CONSTRAINT uk_simulator_webhook_transaction_sequence
        UNIQUE (transaction_id, delivery_sequence),
    CONSTRAINT fk_simulator_webhook_transaction
        FOREIGN KEY (transaction_id)
        REFERENCES simulator.provider_transactions (transaction_id),
    CONSTRAINT ck_simulator_webhook_payload CHECK (
        jsonb_typeof(payload::jsonb) = 'object'
    ),
    CONSTRAINT ck_simulator_webhook_status CHECK (
        status IN ('PENDING', 'CLAIMED', 'RETRYABLE', 'DELIVERED', 'DEAD', 'SUPPRESSED')
    ),
    CONSTRAINT ck_simulator_webhook_attempts CHECK (attempt_count BETWEEN 0 AND 5),
    CONSTRAINT ck_simulator_webhook_sequence CHECK (delivery_sequence BETWEEN 1 AND 2),
    CONSTRAINT ck_simulator_webhook_signature CHECK (
        signature_mode IN ('VALID', 'INVALID')
    ),
    CONSTRAINT ck_simulator_webhook_repeat CHECK (repeat_remaining BETWEEN 0 AND 1),
    CONSTRAINT ck_simulator_webhook_lease CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL)
    )
);

CREATE INDEX ix_simulator_webhook_due
    ON simulator.webhook_deliveries (status, next_attempt_at, lease_expires_at);

CREATE FUNCTION simulator.create_webhook_delivery()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO simulator.webhook_deliveries
        (delivery_id, transaction_id, delivery_sequence, provider_event_id, payload,
         signature_mode, repeat_remaining, status,
         attempt_count, next_attempt_at, created_at, updated_at)
    VALUES (
        NEW.provider_result_id,
        NEW.transaction_id,
        1,
        NEW.provider_result_id,
        jsonb_build_object(
            'providerEventId', lower(NEW.provider_result_id::text),
            'providerResultId', lower(NEW.provider_result_id::text),
            'providerIdempotencyKey', NEW.provider_idempotency_key,
            'providerReference', NEW.provider_reference,
            'providerResultCategory', NEW.result_category,
            'providerOccurredAt', to_char(
                date_trunc('second', NEW.created_at) AT TIME ZONE 'UTC',
                'YYYY-MM-DD"T"HH24:MI:SS"Z"'
            )
        )::text,
        CASE WHEN NEW.scenario = 'INVALID_SIGNATURE' THEN 'INVALID' ELSE 'VALID' END,
        CASE WHEN NEW.scenario = 'DUPLICATE_WEBHOOK' THEN 1 ELSE 0 END,
        CASE WHEN NEW.scenario = 'MISSING_WEBHOOK' THEN 'SUPPRESSED' ELSE 'PENDING' END,
        0,
        CASE WHEN NEW.scenario = 'DELAYED_WEBHOOK'
             THEN NEW.created_at + INTERVAL '5 seconds' ELSE NEW.created_at END,
        NEW.created_at,
        NEW.created_at
    );
    IF NEW.scenario IN ('OUT_OF_ORDER_WEBHOOK', 'CONFLICTING_RESULT') THEN
        DECLARE
            second_id UUID := gen_random_uuid();
            second_category TEXT := CASE
                WHEN NEW.scenario = 'OUT_OF_ORDER_WEBHOOK' THEN 'PENDING'
                ELSE 'DECLINED'
            END;
        BEGIN
            INSERT INTO simulator.webhook_deliveries
                (delivery_id, transaction_id, delivery_sequence, provider_event_id, payload,
                 signature_mode, repeat_remaining, status, attempt_count,
                 next_attempt_at, created_at, updated_at)
            VALUES (
                second_id,
                NEW.transaction_id,
                2,
                second_id,
                jsonb_build_object(
                    'providerEventId', lower(second_id::text),
                    'providerResultId', lower(second_id::text),
                    'providerIdempotencyKey', NEW.provider_idempotency_key,
                    'providerReference', NEW.provider_reference,
                    'providerResultCategory', second_category,
                    'providerOccurredAt', to_char(
                        date_trunc('second', NEW.created_at) AT TIME ZONE 'UTC',
                        'YYYY-MM-DD"T"HH24:MI:SS"Z"'
                    )
                )::text,
                'VALID',
                0,
                'PENDING',
                0,
                NEW.created_at + INTERVAL '1 second',
                NEW.created_at,
                NEW.created_at
            );
        END;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER provider_transaction_webhook_delivery
AFTER INSERT ON simulator.provider_transactions
FOR EACH ROW EXECUTE FUNCTION simulator.create_webhook_delivery();

CREATE FUNCTION simulator.reject_webhook_delivery_business_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.delivery_id IS DISTINCT FROM OLD.delivery_id
       OR NEW.transaction_id IS DISTINCT FROM OLD.transaction_id
       OR NEW.delivery_sequence IS DISTINCT FROM OLD.delivery_sequence
       OR NEW.provider_event_id IS DISTINCT FROM OLD.provider_event_id
       OR NEW.payload IS DISTINCT FROM OLD.payload
       OR NEW.signature_mode IS DISTINCT FROM OLD.signature_mode
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Simulator webhook delivery business content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER simulator_webhook_delivery_business_immutable
BEFORE UPDATE ON simulator.webhook_deliveries
FOR EACH ROW EXECUTE FUNCTION simulator.reject_webhook_delivery_business_mutation();

CREATE FUNCTION simulator.reject_webhook_delivery_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Simulator webhook delivery cannot be deleted';
END;
$$;

CREATE TRIGGER simulator_webhook_delivery_delete_prohibited
BEFORE DELETE ON simulator.webhook_deliveries
FOR EACH ROW EXECUTE FUNCTION simulator.reject_webhook_delivery_delete();
