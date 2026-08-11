ALTER TABLE simulator.provider_transactions
    ADD COLUMN request_payload JSONB NOT NULL DEFAULT '{}'::jsonb;

UPDATE simulator.provider_transactions
   SET request_payload = '{}'::jsonb
 WHERE request_payload IS NULL;

ALTER TABLE simulator.provider_transactions
    ALTER COLUMN request_payload SET NOT NULL,
    ADD CONSTRAINT ck_simulator_request_payload_shape CHECK (
        jsonb_typeof(request_payload) = 'object'
    );
