-- Optimistic-lock version for products.
--
-- Two jobs: concurrent writers can no longer silently overwrite each other (a stale update fails
-- instead of committing), and every committed change gets a strictly increasing number. That
-- number travels in product lifecycle events so a consumer can refuse an older product state that
-- arrives after a newer one - wall-clock updated_at cannot guarantee that.
ALTER TABLE products ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
