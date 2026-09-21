CREATE TABLE inventory_items (
    product_id      VARCHAR(100) PRIMARY KEY,
    available_stock INTEGER      NOT NULL
);

CREATE TABLE inventory_reservations (
    order_id     VARCHAR(100) PRIMARY KEY,
    product_id   VARCHAR(100) NOT NULL,
    quantity     INTEGER      NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);

-- Demo seed data, per the POC's fixed scenarios.
INSERT INTO inventory_items (product_id, available_stock) VALUES
    ('PROD-1', 100),
    ('PROD-2', 50),
    ('PROD-OUT-OF-STOCK', 0);
