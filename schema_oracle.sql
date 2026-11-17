-- Oracle schema + seed data for the sample app (run on the PRIMARY; True Cache serves reads of the same tables).

CREATE TABLE customers (
  customer_id NUMBER PRIMARY KEY,
  name        VARCHAR2(100) NOT NULL
);

CREATE TABLE products (
  product_id  NUMBER PRIMARY KEY,
  name        VARCHAR2(100) NOT NULL,
  category    VARCHAR2(30)  NOT NULL,
  price_cents NUMBER        NOT NULL,
  stock       NUMBER        NOT NULL CHECK (stock >= 0)
);
CREATE INDEX products_category_ix ON products (category, name);

CREATE TABLE orders (
  order_id    VARCHAR2(32) PRIMARY KEY,
  customer_id NUMBER       NOT NULL,
  product_id  NUMBER       NOT NULL,
  qty         NUMBER       NOT NULL,
  status      VARCHAR2(16) NOT NULL,
  created_at  NUMBER       NOT NULL            -- epoch seconds
);
CREATE INDEX orders_cust_ix ON orders (customer_id, created_at);

-- One row per agent tool call that wrote data; makes retries safe.
CREATE TABLE idempotency_keys (
  idem_key    VARCHAR2(100) PRIMARY KEY,
  result_json VARCHAR2(2000) NOT NULL,
  created_at  NUMBER         NOT NULL
);

INSERT INTO products VALUES (101, 'Studio Headphones',   'audio',       12900, 25);
INSERT INTO products VALUES (102, 'Bluetooth Speaker',   'audio',        7900, 40);
INSERT INTO products VALUES (103, 'USB-C Dock',          'accessories',  9900, 15);
INSERT INTO products VALUES (104, 'Mechanical Keyboard', 'accessories', 14900,  3);
INSERT INTO products VALUES (105, '4K Monitor',          'displays',    34900,  8);
INSERT INTO products VALUES (106, 'Webcam HD',           'accessories',  5900, 30);
COMMIT;
