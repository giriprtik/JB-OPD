-- Transactional outbox: work for other services, written in the same transaction as the state change
-- that needs it, then delivered by a relay. Delivery is at-least-once; request_id is the receiver's
-- idempotency key, so a repeated delivery never creates a duplicate.
CREATE TABLE outbox_message (
    id              BIGSERIAL PRIMARY KEY,
    request_id      UUID         NOT NULL UNIQUE,
    aggregate_id    BIGINT       NOT NULL,                  -- the visit
    message_type    VARCHAR(40)  NOT NULL,                  -- LAB_ORDER_REQUESTED
    payload         TEXT         NOT NULL,                  -- JSON document
    status          VARCHAR(12)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMPTZ
);

CREATE INDEX idx_outbox_due ON outbox_message (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_message (aggregate_id);
