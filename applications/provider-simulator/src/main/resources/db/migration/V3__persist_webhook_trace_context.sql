ALTER TABLE simulator.provider_transactions
    ADD COLUMN traceparent VARCHAR(128),
    ADD COLUMN tracestate VARCHAR(512),
    ADD CONSTRAINT ck_simulator_transaction_traceparent CHECK (
        traceparent IS NULL OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
    );

ALTER TABLE simulator.webhook_deliveries
    ADD COLUMN traceparent VARCHAR(128),
    ADD COLUMN tracestate VARCHAR(512),
    ADD CONSTRAINT ck_simulator_webhook_traceparent CHECK (
        traceparent IS NULL OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
    );

CREATE FUNCTION simulator.populate_webhook_trace_context()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    SELECT traceparent, tracestate
      INTO NEW.traceparent, NEW.tracestate
      FROM simulator.provider_transactions
     WHERE transaction_id = NEW.transaction_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER simulator_webhook_trace_context
BEFORE INSERT ON simulator.webhook_deliveries
FOR EACH ROW EXECUTE FUNCTION simulator.populate_webhook_trace_context();

CREATE OR REPLACE FUNCTION simulator.reject_webhook_delivery_business_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.delivery_id IS DISTINCT FROM OLD.delivery_id
       OR NEW.transaction_id IS DISTINCT FROM OLD.transaction_id
       OR NEW.delivery_sequence IS DISTINCT FROM OLD.delivery_sequence
       OR NEW.provider_event_id IS DISTINCT FROM OLD.provider_event_id
       OR NEW.payload IS DISTINCT FROM OLD.payload
       OR NEW.signature_mode IS DISTINCT FROM OLD.signature_mode
       OR NEW.traceparent IS DISTINCT FROM OLD.traceparent
       OR NEW.tracestate IS DISTINCT FROM OLD.tracestate
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Simulator webhook delivery business content is immutable';
    END IF;
    RETURN NEW;
END;
$$;
