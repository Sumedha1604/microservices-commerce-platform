CREATE TABLE carts (cart_id UUID PRIMARY KEY,user_id UUID NOT NULL UNIQUE,created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL);
CREATE TABLE cart_items (cart_item_id UUID PRIMARY KEY,cart_id UUID NOT NULL REFERENCES carts(cart_id) ON DELETE CASCADE,product_id UUID NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,UNIQUE(cart_id,product_id));
CREATE INDEX idx_cart_items_cart_id ON cart_items(cart_id);
