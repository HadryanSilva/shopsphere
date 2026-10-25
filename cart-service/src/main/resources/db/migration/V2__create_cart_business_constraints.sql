
-- V2__create_cart_business_constraints.sql

-- Cada cliente pode possuir apenas um carrinho ACTIVE.
-- Carrinhos históricos continuam permitidos.

CREATE UNIQUE INDEX uk_carts_active_customer
    ON carts (customer_id)
    WHERE status = 'ACTIVE';


-- Um mesmo produto não pode aparecer duas vezes
-- dentro do mesmo carrinho.

ALTER TABLE cart_items
    ADD CONSTRAINT uk_cart_items_cart_product
        UNIQUE (cart_id, product_id);
