ALTER TABLE messaging.outbox
    DROP CONSTRAINT ck_outbox_producer;

ALTER TABLE messaging.outbox
    ADD CONSTRAINT ck_outbox_producer
        CHECK (producer_name IN (
            'payment', 'provider', 'tenancy', 'merchant', 'identity',
            'risk', 'reconciliation', 'casework'
        ));

ALTER TABLE risk.risk_reviews
    ADD COLUMN merchant_id UUID;

UPDATE risk.risk_reviews review
   SET merchant_id = payment.merchant_id
  FROM payment.payments payment
 WHERE payment.tenant_id = review.tenant_id
   AND payment.id = review.payment_id
   AND review.merchant_id IS NULL;

CREATE INDEX ix_risk_reviews_merchant_queue
    ON risk.risk_reviews (tenant_id, merchant_id, status, due_at, priority DESC, id);
