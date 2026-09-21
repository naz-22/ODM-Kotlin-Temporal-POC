CREATE TABLE deviations (
    id             UUID PRIMARY KEY,
    order_id       VARCHAR(100) NOT NULL,
    type           VARCHAR(40)  NOT NULL,
    attempt_number INTEGER      NOT NULL,
    reason         VARCHAR(500) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    decision       VARCHAR(20),
    created_at     TIMESTAMPTZ  NOT NULL,
    resolved_at    TIMESTAMPTZ,
    CONSTRAINT uq_deviations_order_type_attempt UNIQUE (order_id, type, attempt_number)
);

CREATE INDEX idx_deviations_order_id ON deviations (order_id);
