-- ODM autorecovery (Scenarios 5/6). Separate tables; the existing deviations table is untouched.

CREATE TABLE recovery_deviations (
    deviation_key          VARCHAR(100) PRIMARY KEY, -- upstream idempotency/correlation key
    order_id               VARCHAR(100) NOT NULL,
    type                   VARCHAR(40)  NOT NULL,
    release_stage          VARCHAR(20)  NOT NULL,
    market                 VARCHAR(10)  NOT NULL,
    item_id                VARCHAR(100),
    requested_quantity     INTEGER,
    picked_quantity        INTEGER,
    reason                 VARCHAR(500) NOT NULL,
    auto_recovery_enabled  BOOLEAN      NOT NULL,
    decision               VARCHAR(20)  NOT NULL,
    decision_rule          VARCHAR(200) NOT NULL,
    status                 VARCHAR(40)  NOT NULL,
    resolution             VARCHAR(30),
    resolution_note        VARCHAR(500),
    workflow_id            VARCHAR(150) NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL,
    resolved_at            TIMESTAMPTZ
);

CREATE INDEX idx_recovery_deviations_order_id ON recovery_deviations (order_id);

CREATE TABLE recovery_alerts (
    id             UUID PRIMARY KEY,
    deviation_key  VARCHAR(100) NOT NULL REFERENCES recovery_deviations (deviation_key),
    status         VARCHAR(20)  NOT NULL,
    owner          VARCHAR(20)  NOT NULL,
    reason         VARCHAR(500) NOT NULL,
    opened_at      TIMESTAMPTZ  NOT NULL,
    closed_at      TIMESTAMPTZ,
    CONSTRAINT uq_recovery_alerts_deviation UNIQUE (deviation_key)
);
