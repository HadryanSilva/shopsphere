
-- V1__create_initial_schema.sql

CREATE TABLE categories (
                            id UUID PRIMARY KEY,
                            name VARCHAR(100) NOT NULL,
                            description VARCHAR(500),
                            active BOOLEAN NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                            CONSTRAINT uk_categories_name UNIQUE (name)
);

CREATE TABLE products (
                          id UUID PRIMARY KEY,
                          name VARCHAR(150) NOT NULL,
                          description VARCHAR(2000),
                          category_id UUID NOT NULL,
                          price NUMERIC(19, 2) NOT NULL,
                          currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
                          image_url VARCHAR(2048),
                          status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                          version NUMERIC NOT NULL,
                          created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                          CONSTRAINT fk_products_category
                              FOREIGN KEY (category_id)
                                  REFERENCES categories (id),

                          CONSTRAINT chk_products_price
                              CHECK (price > 0),

                          CONSTRAINT chk_products_currency
                              CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT chk_products_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'OUT_OF_STOCK'))
);

CREATE INDEX idx_products_category_id
    ON products (category_id);

CREATE INDEX idx_products_status
    ON products (status);

CREATE INDEX idx_products_name
    ON products (name);
