-- Idempotency key for order creation. OPD's outbox relay may deliver the same order more than once
-- (at-least-once); a repeated request_id returns the existing order instead of creating a duplicate.
-- Nullable so older callers without a key keep working.
ALTER TABLE lab_orders ADD COLUMN request_id UUID;

CREATE UNIQUE INDEX uq_lab_orders_request_id ON lab_orders (request_id) WHERE request_id IS NOT NULL;
