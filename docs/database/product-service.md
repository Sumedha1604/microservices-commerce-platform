# Product Service database

Migration `V1__create_product_schema.sql` creates `categories`, `brands`, `products`, `product_images`, and `product_attributes`. `products.category_id` and `products.brand_id` reference `categories`/`brands`; `product_images` and `product_attributes` reference `products`. `categories.parent_category_id` is self-referential for category hierarchy. Foreign keys are internal to this service only.
