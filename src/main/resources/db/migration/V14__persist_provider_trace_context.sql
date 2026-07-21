ALTER TABLE provider.work
    ADD COLUMN traceparent VARCHAR(128),
    ADD COLUMN tracestate VARCHAR(512),
    ADD CONSTRAINT ck_provider_work_traceparent CHECK (
        traceparent IS NULL OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
    );

ALTER TABLE provider.webhook_events
    ADD COLUMN traceparent VARCHAR(128),
    ADD COLUMN tracestate VARCHAR(512),
    ADD CONSTRAINT ck_provider_webhook_traceparent CHECK (
        traceparent IS NULL OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
    );

CREATE OR REPLACE FUNCTION provider.reject_work_business_content_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.payment_id IS DISTINCT FROM OLD.payment_id
       OR NEW.attempt_sequence IS DISTINCT FROM OLD.attempt_sequence
       OR NEW.work_type IS DISTINCT FROM OLD.work_type
       OR NEW.provider_id IS DISTINCT FROM OLD.provider_id
       OR NEW.provider_idempotency_key IS DISTINCT FROM OLD.provider_idempotency_key
       OR NEW.request_intent_hash IS DISTINCT FROM OLD.request_intent_hash
       OR NEW.command_payload IS DISTINCT FROM OLD.command_payload
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.causation_id IS DISTINCT FROM OLD.causation_id
       OR NEW.traceparent IS DISTINCT FROM OLD.traceparent
       OR NEW.tracestate IS DISTINCT FROM OLD.tracestate
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Provider work business identity and content are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION provider.reject_webhook_event_business_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.event_id IS DISTINCT FROM OLD.event_id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.provider_id IS DISTINCT FROM OLD.provider_id
       OR NEW.provider_client_id IS DISTINCT FROM OLD.provider_client_id
       OR NEW.provider_event_id IS DISTINCT FROM OLD.provider_event_id
       OR NEW.payment_id IS DISTINCT FROM OLD.payment_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.attempt_sequence IS DISTINCT FROM OLD.attempt_sequence
       OR NEW.provider_idempotency_key IS DISTINCT FROM OLD.provider_idempotency_key
       OR NEW.provider_result_id IS DISTINCT FROM OLD.provider_result_id
       OR NEW.provider_reference IS DISTINCT FROM OLD.provider_reference
       OR NEW.result_category IS DISTINCT FROM OLD.result_category
       OR NEW.provider_occurred_at IS DISTINCT FROM OLD.provider_occurred_at
       OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash
       OR NEW.canonical_payload IS DISTINCT FROM OLD.canonical_payload
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.traceparent IS DISTINCT FROM OLD.traceparent
       OR NEW.tracestate IS DISTINCT FROM OLD.tracestate
       OR NEW.received_at IS DISTINCT FROM OLD.received_at THEN
        RAISE EXCEPTION 'Provider webhook event business content is immutable';
    END IF;
    RETURN NEW;
END;
$$;
