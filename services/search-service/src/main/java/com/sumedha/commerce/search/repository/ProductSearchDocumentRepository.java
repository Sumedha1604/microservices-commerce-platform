package com.sumedha.commerce.search.repository;

import com.sumedha.commerce.common.events.product.ProductUpsertedEvent;
import com.sumedha.commerce.search.dto.response.ProductSearchResult;
import com.sumedha.commerce.search.service.ProductSearchCriteria;
import com.sumedha.commerce.search.service.SearchSort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The search read model, in plain SQL.
 *
 * <p><strong>Writes are version-guarded single statements.</strong> An upsert or tombstone only
 * changes the row when the incoming {@code source_version} is strictly newer than the stored one, so
 * an old state that arrives late - redelivered, replayed, or overtaken - reports {@code 0} rows and
 * changes nothing. Because the guard lives in the statement, two concurrent writers for one product
 * serialize on the row and the newer version always wins.
 *
 * <p><strong>Reads</strong> combine PostgreSQL full-text search (a weighted, generated
 * {@code tsvector}) with trigram substring matching on a generated lower-cased text column, both
 * GIN-indexed. Every dynamic piece of SQL is either a bound parameter or chosen from a fixed
 * whitelist ({@link SearchSort}); no request text is ever concatenated into the statement.
 */
@Repository
public class ProductSearchDocumentRepository {

    private static final String UPSERT = """
            insert into product_search_document (product_id, sku, name, slug, short_description, description,
                category_id, brand_id, price, currency, status, active, deleted, source_version, source_updated_at,
                last_event_id, indexed_at)
            values (:productId, :sku, :name, :slug, :shortDescription, :description, :categoryId, :brandId, :price,
                :currency, :status, :active, false, :version, :updatedAt, :eventId, now())
            on conflict (product_id) do update set
                sku = excluded.sku, name = excluded.name, slug = excluded.slug,
                short_description = excluded.short_description, description = excluded.description,
                category_id = excluded.category_id, brand_id = excluded.brand_id, price = excluded.price,
                currency = excluded.currency, status = excluded.status, active = excluded.active,
                source_version = excluded.source_version, source_updated_at = excluded.source_updated_at,
                last_event_id = excluded.last_event_id, indexed_at = excluded.indexed_at
            where product_search_document.source_version < excluded.source_version
              and not product_search_document.deleted
            """;

    private static final String TOMBSTONE = """
            insert into product_search_document (product_id, deleted, source_version, last_event_id, indexed_at)
            values (:productId, true, :version, :eventId, now())
            on conflict (product_id) do update set
                deleted = true, sku = null, name = null, slug = null, short_description = null, description = null,
                category_id = null, brand_id = null, price = null, currency = null, status = null, active = null,
                source_version = excluded.source_version, source_updated_at = null,
                last_event_id = excluded.last_event_id, indexed_at = excluded.indexed_at
            where product_search_document.source_version < excluded.source_version
            """;

    private static final String COLUMNS = "d.product_id, d.sku, d.name, d.slug, d.short_description, d.description, "
            + "d.category_id, d.brand_id, d.price, d.currency, d.status, d.source_version, d.source_updated_at, d.indexed_at";

    /** Exact name/SKU match, then name prefix, then name substring, then full-text rank and name similarity. */
    private static final String RELEVANCE_SCORE = "(case when lower(d.name) = :qLower or lower(d.sku) = :qLower then 3.0 else 0.0 end"
            + " + case when lower(d.name) like :prefix escape '\\' then 1.5 else 0.0 end"
            + " + case when lower(d.name) like :contains escape '\\' then 1.0 else 0.0 end"
            + " + ts_rank(d.search_vector, websearch_to_tsquery('english', :q))"
            + " + similarity(lower(d.name), :qLower))";

    private static final RowMapper<ProductSearchResult> ROW = (rs, rowNum) -> new ProductSearchResult(
            rs.getObject("product_id", UUID.class),
            rs.getString("sku"),
            rs.getString("name"),
            rs.getString("slug"),
            rs.getString("short_description"),
            rs.getString("description"),
            rs.getObject("category_id", UUID.class),
            rs.getObject("brand_id", UUID.class),
            rs.getBigDecimal("price"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getLong("source_version"),
            instant(rs.getObject("source_updated_at", OffsetDateTime.class)),
            instant(rs.getObject("indexed_at", OffsetDateTime.class)));

    private final NamedParameterJdbcTemplate jdbc;

    public ProductSearchDocumentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code 1} if the state was applied, {@code 0} if the stored state is as new or newer, or deleted */
    public int upsert(UUID eventId, ProductUpsertedEvent product) {
        return jdbc.update(UPSERT, new MapSqlParameterSource()
                .addValue("productId", product.productId(), Types.OTHER)
                .addValue("sku", product.sku())
                .addValue("name", product.name())
                .addValue("slug", product.slug())
                .addValue("shortDescription", product.shortDescription())
                .addValue("description", product.description())
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

    public SearchPage search(ProductSearchCriteria criteria) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("status", criteria.status());
        StringBuilder where = new StringBuilder(" where not d.deleted and d.active and d.status = :status");

        boolean text = criteria.query() != null;
        if (text) {
            String lowered = criteria.query().toLowerCase(Locale.ROOT);
            params.addValue("q", criteria.query())
                    .addValue("qLower", lowered)
                    .addValue("contains", "%" + escapeLike(lowered) + "%")
                    .addValue("prefix", escapeLike(lowered) + "%");
            where.append(" and (d.search_vector @@ websearch_to_tsquery('english', :q)"
                    + " or d.search_text like :contains escape '\\')");
        }
        if (criteria.categoryId() != null) {
            where.append(" and d.category_id = :categoryId");
            params.addValue("categoryId", criteria.categoryId(), Types.OTHER);
        }
        if (criteria.brandId() != null) {
            where.append(" and d.brand_id = :brandId");
            params.addValue("brandId", criteria.brandId(), Types.OTHER);
        }
        if (criteria.currency() != null) {
            where.append(" and d.currency = :currency");
            params.addValue("currency", criteria.currency());
        }
        if (criteria.minPrice() != null) {
            where.append(" and d.price >= :minPrice");
            params.addValue("minPrice", criteria.minPrice());
        }
        if (criteria.maxPrice() != null) {
            where.append(" and d.price <= :maxPrice");
            params.addValue("maxPrice", criteria.maxPrice());
        }

        Long total = jdbc.queryForObject("select count(*) from product_search_document d" + where, params, Long.class);
        long offset = (long) criteria.page() * criteria.size();
        if (total == null || total == 0 || offset >= total) {
            return new SearchPage(List.of(), total == null ? 0 : total);
        }

        params.addValue("limit", criteria.size()).addValue("offset", offset);
        String sql = "select " + COLUMNS + ", " + (text ? RELEVANCE_SCORE : "0.0") + " as score"
                + " from product_search_document d" + where
                + " order by " + orderBy(criteria.sort(), text)
                + " limit :limit offset :offset";
        return new SearchPage(jdbc.query(sql, params, ROW), total);
    }

    /** Whitelisted ORDER BY per sort mode; every mode ends in a unique key so pages never overlap. */
    static String orderBy(SearchSort sort, boolean text) {
        return switch (sort) {
            case RELEVANCE -> text ? "score desc, d.name asc, d.product_id asc" : "d.name asc, d.product_id asc";
            case PRICE_ASC -> "d.price asc, d.name asc, d.product_id asc";
            case PRICE_DESC -> "d.price desc, d.name asc, d.product_id asc";
            case NAME_ASC -> "d.name asc, d.product_id asc";
            case NAME_DESC -> "d.name desc, d.product_id asc";
        };
    }

    /** Makes user text literal inside a LIKE pattern (escape character is backslash). */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record SearchPage(List<ProductSearchResult> items, long total) {
    }
}
