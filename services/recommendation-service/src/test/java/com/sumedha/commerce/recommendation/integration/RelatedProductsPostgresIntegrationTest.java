package com.sumedha.commerce.recommendation.integration;

import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.recommendation.dto.response.ProductRecommendation;
import com.sumedha.commerce.recommendation.messaging.ProductEventParser;
import com.sumedha.commerce.recommendation.messaging.ProductProjectionProcessor;
import com.sumedha.commerce.recommendation.service.RecommendationCandidate;
import com.sumedha.commerce.recommendation.service.RecommendationScoring.Reason;
import com.sumedha.commerce.recommendation.service.RecommendationService;
import com.sumedha.commerce.recommendation.service.RelatedProductRanker;
import com.sumedha.commerce.recommendation.support.ProductEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static com.sumedha.commerce.recommendation.support.ProductEvents.deleted;
import static com.sumedha.commerce.recommendation.support.ProductEvents.product;
import static com.sumedha.commerce.recommendation.support.ProductEvents.upserted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Related-product ranking against real PostgreSQL, over a catalogue built through the event path.
 * Includes a differential check: the SQL ranking equals {@link RelatedProductRanker} - the Java
 * statement of the rules - for the same projection.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:59990",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
@Testcontainers
class RelatedProductsPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("recommendation_test")
            .withUsername("recommendation")
            .withPassword("recommendation");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ProductEventParser parser;
    @Autowired private ProductProjectionProcessor processor;
    @Autowired private RecommendationService recommendations;
    @Autowired private JdbcTemplate jdbc;

    private final UUID phones = UUID.randomUUID();
    private final UUID laptops = UUID.randomUUID();
    private final UUID acme = UUID.randomUUID();
    private final UUID globex = UUID.randomUUID();

    private ProductEvents.Product source;
    private ProductEvents.Product categoryBrandPrice;   // 8
    private ProductEvents.Product categoryPrice;        // 6
    private ProductEvents.Product categoryFar;          // 5
    private ProductEvents.Product categoryEuro;         // 5, no price bonus across currencies
    private ProductEvents.Product brandPrice;           // 3
    private ProductEvents.Product brandFar;             // 2
    private ProductEvents.Product unrelated;            // omitted
    private ProductEvents.Product draft;                // excluded
    private ProductEvents.Product inactive;             // excluded
    private ProductEvents.Product deletedCandidate;     // excluded

    @BeforeEach
    void seed() {
        jdbc.update("delete from recommendation_product");
        jdbc.update("delete from processed_event");

        source = index(product().name("Source Phone").category(phones).brand(acme).price("100.00"));
        categoryBrandPrice = index(product().name("Acme Phone Mini").category(phones).brand(acme).price("110.00"));
        categoryPrice = index(product().name("Globex Phone").category(phones).brand(globex).price("100.00"));
        categoryFar = index(product().name("Budget Phone").category(phones).price("500.00"));
        categoryEuro = index(product().name("Euro Phone").category(phones).brand(globex).price("100.00").currency("EUR"));
        brandPrice = index(product().name("Acme Laptop Lite").category(laptops).brand(acme).price("95.00"));
        brandFar = index(product().name("Acme Laptop Pro").category(laptops).brand(acme).price("1000.00"));
        unrelated = index(product().name("Globex Laptop").category(laptops).brand(globex).price("100.00"));
        draft = index(product().name("Draft Phone").category(phones).brand(acme).price("100.00").status("DRAFT"));
        inactive = index(product().name("Inactive Phone").category(phones).brand(acme).price("100.00").active(false));
        deletedCandidate = index(product().name("Deleted Phone").category(phones).brand(acme).price("100.00").version(1));
        processor.process(parser.parse(deleted(UUID.randomUUID(), deletedCandidate.productId, 2)));
    }

    private ProductEvents.Product index(ProductEvents.Product product) {
        processor.process(parser.parse(upserted(UUID.randomUUID(), product)));
        return product;
    }

    private List<ProductRecommendation> related(UUID sourceId, int limit) {
        return recommendations.relatedProducts(sourceId, limit).items();
    }

    private static List<UUID> ids(List<ProductRecommendation> items) {
        return items.stream().map(ProductRecommendation::productId).toList();
    }

    // ---------- 9, 12: documented ordering and scores ----------

    @Test
    void relatedProductsAreRankedByCategoryThenBrandThenPriceWithStableTies() {
        List<ProductRecommendation> items = related(source.productId, 50);

        // Budget Phone and Euro Phone both score 5; "Budget" < "Euro" by name.
        assertEquals(List.of(categoryBrandPrice.productId, categoryPrice.productId, categoryFar.productId,
                categoryEuro.productId, brandPrice.productId, brandFar.productId), ids(items));
        assertEquals(List.of(8, 6, 5, 5, 3, 2), items.stream().map(ProductRecommendation::score).toList());
        assertEquals(List.of(Reason.SAME_CATEGORY, Reason.SAME_BRAND, Reason.SIMILAR_PRICE), items.get(0).reasons());
        assertEquals(List.of(Reason.SAME_CATEGORY, Reason.SIMILAR_PRICE), items.get(1).reasons());
        assertEquals(List.of(Reason.SAME_CATEGORY), items.get(3).reasons(), "a EUR price never earns the USD price bonus");
        assertEquals(List.of(Reason.SAME_BRAND, Reason.SIMILAR_PRICE), items.get(4).reasons());
    }

    @Test
    void theSameRequestAlwaysReturnsTheSameOrder() {
        List<UUID> first = ids(related(source.productId, 50));
        for (int i = 0; i < 5; i++) {
            assertEquals(first, ids(related(source.productId, 50)));
        }
    }

    // ---------- 10, 11: exclusions ----------

    @Test
    void theSourceUnrelatedInactiveDraftAndDeletedProductsAreNotRecommended() {
        List<UUID> recommended = ids(related(source.productId, 50));

        assertFalse(recommended.contains(source.productId), "source excluded");
        assertFalse(recommended.contains(unrelated.productId), "no shared category or brand");
        assertFalse(recommended.contains(draft.productId), "DRAFT excluded");
        assertFalse(recommended.contains(inactive.productId), "active=false excluded");
        assertFalse(recommended.contains(deletedCandidate.productId), "tombstone excluded");
    }

    @Test
    void deactivatingOrDeletingACandidateRemovesIt() {
        index(categoryBrandPrice.status("INACTIVE").version(1));
        processor.process(parser.parse(deleted(UUID.randomUUID(), categoryPrice.productId, 1)));

        List<UUID> recommended = ids(related(source.productId, 50));

        assertEquals(List.of(categoryFar.productId, categoryEuro.productId, brandPrice.productId, brandFar.productId), recommended);
    }

    @Test
    void theLimitIsAppliedInTheDatabase() {
        assertEquals(List.of(categoryBrandPrice.productId, categoryPrice.productId), ids(related(source.productId, 2)));
        assertEquals(1, related(source.productId, 1).size());
    }

    // ---------- source handling ----------

    @Test
    void anUnknownOrDeletedSourceIsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> related(UUID.randomUUID(), 10));
        assertThrows(ResourceNotFoundException.class, () -> related(deletedCandidate.productId, 10));
    }

    @Test
    void anInactiveSourceStillGetsActiveRecommendations() {
        List<UUID> forInactiveSource = ids(related(inactive.productId, 50));

        assertTrue(forInactiveSource.contains(categoryBrandPrice.productId), forInactiveSource::toString);
        assertFalse(forInactiveSource.contains(inactive.productId));
        assertFalse(forInactiveSource.contains(draft.productId));
    }

    @Test
    void aSourceWithNothingRelatedGetsAnEmptyList() {
        ProductEvents.Product loner = index(product().name("Loner").category(UUID.randomUUID()));

        assertTrue(related(loner.productId, 10).isEmpty());
    }

    // ---------- differential: SQL == Java statement of the rules ----------

    @Test
    void theSqlRankingMatchesTheJavaRankerForTheSeededCatalogue() {
        assertSqlMatchesJava(List.of(source.productId, brandFar.productId, categoryEuro.productId, inactive.productId), 50);
    }

    /**
     * A larger random catalogue - mixed-case names, duplicate names, missing brands, two currencies,
     * all statuses, deletions, prices on and around the 20% band - so collation, NULL brands, currency
     * handling and tie-breaking are all exercised against the reference implementation.
     */
    @Test
    void theSqlRankingMatchesTheJavaRankerForARandomCatalogue() {
        Random random = new Random(20260911L);
        UUID[] categories = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        UUID[] brands = {UUID.randomUUID(), UUID.randomUUID(), null};
        String[] names = {"alpha", "Alpha", "beta", "Beta", "gamma", "Zeta", "zeta", "Omega", "omega", "Delta"};
        String[] currencies = {"USD", "USD", "USD", "EUR"};
        String[] statuses = {"ACTIVE", "ACTIVE", "ACTIVE", "DRAFT", "INACTIVE", "DISCONTINUED"};
        String[] prices = {"80.00", "79.99", "100.00", "120.00", "120.01", "150.00", "0.00", "95.50"};
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            ProductEvents.Product p = index(product()
                    .name(names[random.nextInt(names.length)])
                    .category(categories[random.nextInt(categories.length)])
                    .brand(brands[random.nextInt(brands.length)])
                    .currency(currencies[random.nextInt(currencies.length)])
                    .status(statuses[random.nextInt(statuses.length)])
                    .active(random.nextInt(5) != 0)
                    .price(prices[random.nextInt(prices.length)])
                    .version(1));
            if (random.nextInt(10) == 0) {
                processor.process(parser.parse(deleted(UUID.randomUUID(), p.productId, 2)));
            } else {
                ids.add(p.productId);
            }
        }

        assertSqlMatchesJava(ids, 50);
        assertSqlMatchesJava(ids.subList(0, 10), 5);
    }

    private void assertSqlMatchesJava(List<UUID> sources, int limit) {
        List<RecommendationCandidate> catalogue = jdbc.query(
                "select product_id, name, slug, category_id, brand_id, price, currency, status, coalesce(active, false) active, deleted "
                        + "from recommendation_product",
                (rs, n) -> new RecommendationCandidate(rs.getObject("product_id", UUID.class), rs.getString("name"),
                        rs.getString("slug"), rs.getObject("category_id", UUID.class), rs.getObject("brand_id", UUID.class),
                        rs.getBigDecimal("price"), rs.getString("currency"), rs.getString("status"),
                        rs.getBoolean("active"), rs.getBoolean("deleted")));
        for (UUID sourceId : sources) {
            RecommendationCandidate sourceCandidate = catalogue.stream()
                    .filter(c -> c.productId().equals(sourceId)).findFirst().orElseThrow();
            List<ProductRecommendation> expected = RelatedProductRanker.rank(sourceCandidate, catalogue, limit);
            List<ProductRecommendation> actual = related(sourceId, limit);
            assertEquals(ids(expected), ids(actual), "ordering for source " + sourceId);
            assertEquals(expected.stream().map(ProductRecommendation::score).toList(),
                    actual.stream().map(ProductRecommendation::score).toList(), "scores for source " + sourceId);
            assertEquals(expected.stream().map(ProductRecommendation::reasons).toList(),
                    actual.stream().map(ProductRecommendation::reasons).toList(), "reasons for source " + sourceId);
        }
    }
}
