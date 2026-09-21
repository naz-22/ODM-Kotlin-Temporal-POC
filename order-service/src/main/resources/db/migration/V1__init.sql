CREATE TABLE orders (
    id           UUID PRIMARY KEY,
    customer_id  VARCHAR(100)   NOT NULL,
    product_id   VARCHAR(100)   NOT NULL,
    quantity     INTEGER        NOT NULL,
    amount       NUMERIC(12, 2) NOT NULL,
    status       VARCHAR(40)    NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status);
