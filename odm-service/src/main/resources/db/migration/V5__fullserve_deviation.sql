-- Full-Serve deviation lifecycle. New tables only; V1-V4 tables are untouched.

CREATE TABLE fullserve_deviations (
    deviation_id      VARCHAR(100) PRIMARY KEY,
    order_id          VARCHAR(100) NOT NULL,
    source            VARCHAR(10)  NOT NULL,
    service_type      VARCHAR(30)  NOT NULL,
    market            VARCHAR(10)  NOT NULL,
    deviation_type    VARCHAR(100) NOT NULL,
    reason_code       VARCHAR(100) NOT NULL,
    occurrence        VARCHAR(50)  NOT NULL,
    reason_caused     VARCHAR(200),
    status            VARCHAR(20)  NOT NULL,
    workflow_id       VARCHAR(150) NOT NULL,
    run_id            VARCHAR(100) NOT NULL,
    business_rule     VARCHAR(500),
    closure_trigger   VARCHAR(30),
    closure_source    VARCHAR(10),
    closure_note      VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL,
    closed_at         TIMESTAMPTZ
);

-- At most one OPEN deviation per order, enforced by the database: a concurrent second insert
-- for the same order conflicts instead of creating a duplicate.
CREATE UNIQUE INDEX uq_fullserve_deviations_open_order ON fullserve_deviations (order_id) WHERE status = 'OPEN';
CREATE INDEX idx_fullserve_deviations_order_id ON fullserve_deviations (order_id);

-- Outcome of every outbound publication (O360, SPM, Dynamics). notification_key is deterministic
-- per deviation/kind/recipient/alert, so a retried Activity sees an earlier PUBLISHED row.
CREATE TABLE fullserve_notifications (
    notification_key  VARCHAR(250) PRIMARY KEY,
    deviation_id      VARCHAR(100) NOT NULL REFERENCES fullserve_deviations (deviation_id),
    kind              VARCHAR(30)  NOT NULL,
    recipient         VARCHAR(50)  NOT NULL,
    destination       VARCHAR(300) NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    detail            VARCHAR(500),
    recorded_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_fullserve_notifications_deviation ON fullserve_notifications (deviation_id);
