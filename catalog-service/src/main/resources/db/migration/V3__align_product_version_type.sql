-- Match the Java long used for optimistic locking without changing applied migrations.
ALTER TABLE products
    ALTER COLUMN version TYPE BIGINT USING version::BIGINT;
