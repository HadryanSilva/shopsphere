
-- V1__insert_default_categories.sql

INSERT INTO categories (
    id,
    name,
    description,
    active,
    created_at,
    updated_at
)
VALUES
    (
        '10000000-0000-0000-0000-000000000001',
        'Electronics',
        'Electronic devices, computers, smartphones and accessories.',
        true,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        '10000000-0000-0000-0000-000000000002',
        'Clothing',
        'Clothing, footwear and fashion accessories.',
        true,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        '10000000-0000-0000-0000-000000000003',
        'Home & Kitchen',
        'Furniture, home decor, kitchenware and household appliances.',
        true,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        '10000000-0000-0000-0000-000000000004',
        'Books',
        'Books, e-books and educational materials.',
        true,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        '10000000-0000-0000-0000-000000000005',
        'Sports & Outdoors',
        'Sports equipment, fitness accessories and outdoor gear.',
        true,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        '10000000-0000-0000-0000-000000000006',
        'Beauty & Personal Care',
        'Beauty products, skincare and personal care items.',
        true,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        '10000000-0000-0000-0000-000000000007',
        'Toys & Games',
        'Toys, board games and entertainment products.',
        true,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        '10000000-0000-0000-0000-000000000008',
        'Food & Beverages',
        'Packaged food, beverages and related products.',
        true,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        '10000000-0000-0000-0000-000000000009',
        'Health & Wellness',
        'General wellness products and health-related accessories.',
        true,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        '10000000-0000-0000-0000-000000000010',
        'Pet Supplies',
        'Pet food, toys and accessories for animals.',
        true,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    );
