CREATE TABLE payment_attempts (
    id             UUID PRIMARY KEY,
    order_id       VARCHAR(100)   NOT NULL,
    attempt_number INTEGER        NOT NULL,
    customer_id    VARCHAR(100)   NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    failure_reason VARCHAR(500),
    created_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_payment_attempts_order_attempt UNIQUE (order_id, attempt_number)
);

CREATE INDEX idx_payment_attempts_order_id ON payment_attempts (order_id);
