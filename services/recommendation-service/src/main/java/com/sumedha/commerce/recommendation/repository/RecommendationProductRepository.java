package com.sumedha.commerce.recommendation.repository;

import com.sumedha.commerce.common.events.product.ProductUpsertedEvent;
import com.sumedha.commerce.recommendation.dto.response.ProductRecommendation;
import com.sumedha.commerce.recommendation.service.RecommendationCandidate;
import com.sumedha.commerce.recommendation.service.RecommendationScoring;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The recommendation projection, in plain SQL.
 *
 * <p><strong>Writes</strong> are version-guarded single statements, exactly as in search-service: a
 * state not strictly newer than the stored {@code source_version} updates 0 rows, and an upsert never
 * overwrites a tombstone.
 *
 * <p><strong>Ranking</strong> happens in one query that binds {@link RecommendationScoring}'s weights,
 * so candidate filtering, scoring and ordering stay in the database and only {@code limit} rows come
 * back. {@code RelatedProductRanker} is the Java statement of the same rules.
 */
@Repository
public class RecommendationProductRepository {

    private static final String UPSERT = """
            insert into recommendation_product (product_id, name, slug, category_id, brand_id, price, currency, status,
                active, deleted, source_version, source_updated_at, last_event_id, indexed_at)
            values (:productId, :name, :slug, :categoryId, :brandId, :price, :currency, :status, :active, false,
                :version, :updatedAt, :eventId, now())
            on conflict (product_id) do update set
                name = excluded.name, slug = excluded.slug, category_id = excluded.category_id,
                brand_id = excluded.brand_id, price = excluded.price, currency = excluded.currency,
                status = excluded.status, active = excluded.active,
                source_version = excluded.source_version, source_updated_at = excluded.source_updated_at,
                last_event_id = excluded.last_event_id, indexed_at = excluded.indexed_at
            where recommendation_product.source_version < excluded.source_version
              and not recommendation_product.deleted
            """;

    private static final String TOMBSTONE = """
            insert into recommendation_product (product_id, deleted, source_version, last_event_id, indexed_at)
            values (:productId, true, :version, :eventId, now())
            on conflict (product_id) do update set
                deleted = true, name = null, slug = null, category_id = null, brand_id = null, price = null,
                currency = null, status = null, active = null,
                source_version = excluded.source_version, source_updated_at = null,
                last_event_id = excluded.last_event_id, indexed_at = excluded.indexed_at
            where recommendation_product.source_version < excluded.source_version
            """;

    private static final String SAME_CATEGORY = "c.category_id = s.category_id";
    private static final String SAME_BRAND = "(s.brand_id is not null and c.brand_id = s.brand_id)";
    private static final String SIMILAR_PRICE = "(c.currency = s.currency and abs(c.price - s.price) <= s.price * :priceBand)";

    private static final String RELATED = "select c.product_id, c.name, c.slug, c.category_id, c.brand_id, c.price, c.currency, "
            + "coalesce(" + SAME_CATEGORY + ", false) as same_category, "
            + "coalesce(" + SAME_BRAND + ", false) as same_brand, "
            + "coalesce(" + SIMILAR_PRICE + ", false) as similar_price "
            + "from recommendation_product s "
            + "join recommendation_product c on c.product_id <> s.product_id "
            + "where s.product_id = :sourceId and not s.deleted "
            + "  and not c.deleted and c.active and c.status = 'ACTIVE' "
            + "  and (" + SAME_CATEGORY + " or " + SAME_BRAND + ") "
            + "order by (case when " + SAME_CATEGORY + " then :categoryPoints else 0 end"
            + " + case when " + SAME_BRAND + " then :brandPoints else 0 end"
            + " + case when " + SIMILAR_PRICE + " then :pricePoints else 0 end) desc, "
            + "c.name collate \"C\" asc, c.product_id asc "
            + "limit :limit";

    private final NamedParameterJdbcTemplate jdbc;

    public RecommendationProductRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code 1} if the state was applied, {@code 0} if the stored state is as new or newer, or deleted */
    public int upsert(UUID eventId, ProductUpsertedEvent product) {
        return jdbc.update(UPSERT, new MapSqlParameterSource()
                .addValue("productId", product.productId(), Types.OTHER)
                .addValue("name", product.name())
                .addValue("slug", product.slug())
                .addValue("categoryId", product.categoryId(), Types.OTHER)
                .addValue("brandId", product.brandId(), Types.OTHER)
                .addValue("price", product.price())
                .addValue("currency", product.currency().toUpperCase(Locale.ROOT))
                .addValue("status", product.status())
                .addValue("active", product.active())
                .addValue("version", product.version())
                .addValue("updatedAt", offset(product.updatedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("eventId", eventId, Types.OTHER));
    }

    /** @return {@code 1} if the product is now a tombstone, {@code 0} if the stored state is as new or newer */
    public int tombstone(UUID eventId, UUID productId, long version) {
        return jdbc.update(TOMBSTONE, new MapSqlParameterSource()
                .addValue("productId", productId, Types.OTHER)
                .addValue("version", version)
                .addValue("eventId", eventId, Types.OTHER));
    }

    /** The live (not deleted) product, whatever its status - an inactive product can still be a source. */
    public Optional<RecommendationCandidate> findLive(UUID productId) {
        return jdbc.query("select product_id, name, slug, category_id, brand_id, price, currency, status, active, deleted "
                        + "from recommendation_product where product_id = :productId and not deleted",
                new MapSqlParameterSource().addValue("productId", productId, Types.OTHER),
                (rs, rowNum) -> new RecommendationCandidate(rs.getObject("product_id", UUID.class), rs.getString("name"),
                        rs.getString("slug"), rs.getObject("category_id", UUID.class), rs.getObject("brand_id", UUID.class),
                        rs.getBigDecimal("price"), rs.getString("currency"), rs.getString("status"),
                        rs.getBoolean("active"), rs.getBoolean("deleted")))
                .stream().findFirst();
    }

    /** Related products for a live source, ranked and limited in the database. */
    public List<ProductRecommendation> findRelated(UUID sourceProductId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sourceId", sourceProductId, Types.OTHER)
                .addValue("priceBand", RecommendationScoring.PRICE_BAND)
                .addValue("categoryPoints", RecommendationScoring.SAME_CATEGORY_POINTS)
                .addValue("brandPoints", RecommendationScoring.SAME_BRAND_POINTS)
                .addValue("pricePoints", RecommendationScoring.SIMILAR_PRICE_POINTS)
                .addValue("limit", limit);
        return jdbc.query(RELATED, params, (rs, rowNum) -> ProductRecommendation.of(
                new RecommendationCandidate(rs.getObject("product_id", UUID.class), rs.getString("name"), rs.getString("slug"),
                        rs.getObject("category_id", UUID.class), rs.getObject("brand_id", UUID.class),
                        rs.getBigDecimal("price"), rs.getString("currency"), "ACTIVE", true, false),
                rs.getBoolean("same_category"), rs.getBoolean("same_brand"), rs.getBoolean("similar_price")));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
