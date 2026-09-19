package com.sumedha.commerce.recommendation.integration;

import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.recommendation.messaging.NonRetryableEventException;
import com.sumedha.commerce.recommendation.messaging.ProductEventParser;
import com.sumedha.commerce.recommendation.messaging.ProductProjectionProcessor;
import com.sumedha.commerce.recommendation.messaging.ProductProjectionProcessor.Outcome;
import com.sumedha.commerce.recommendation.service.RecommendationService;
import com.sumedha.commerce.recommendation.support.ProductEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.sumedha.commerce.recommendation.support.ProductEvents.deleted;
import static com.sumedha.commerce.recommendation.support.ProductEvents.product;
import static com.sumedha.commerce.recommendation.support.ProductEvents.upserted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recommendation projection on real PostgreSQL: what one product event writes, and what duplicate,
 * concurrent, stale, failed or rolled-back deliveries do not write. The listener is parked; broker
 * behaviour is {@code RecommendationKafkaIntegrationTest}.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:59991",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
@Testcontainers
class RecommendationProjectionPostgresIntegrationTest {

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
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        jdbc.update("delete from recommendation_product");
        jdbc.update("delete from processed_event");
    }

    private Outcome apply(String record) {
        return processor.process(parser.parse(record));
    }

    private Map<String, Object> row(UUID productId) {
        return jdbc.queryForMap("select * from recommendation_product where product_id = ?", productId);
    }

    private int markers() {
        return jdbc.queryForObject("select count(*) from processed_event", Integer.class);
    }

    private int rows() {
        return jdbc.queryForObject("select count(*) from recommendation_product", Integer.class);
    }

    // ---------- 1-3 ----------

    @Test
    void aProductUpsertedInsertsTheProjection() {
        UUID brand = UUID.randomUUID();
        ProductEvents.Product phone = product().name("Smart Phone X").brand(brand).price("599.99").currency("usd");
        UUID eventId = UUID.randomUUID();

        assertEquals(Outcome.UPSERTED, apply(upserted(eventId, phone)));

        Map<String, Object> row = row(phone.productId);
        assertEquals("Smart Phone X", row.get("name"));
        assertEquals(phone.slug, row.get("slug"));
        assertEquals(phone.categoryId, row.get("category_id"));
        assertEquals(brand, row.get("brand_id"));
        assertEquals(0, new BigDecimal("599.99").compareTo((BigDecimal) row.get("price")));
        assertEquals("USD", row.get("currency"), "currency is stored upper-case");
        assertEquals("ACTIVE", row.get("status"));
        assertEquals(true, row.get("active"));
        assertEquals(false, row.get("deleted"));
        assertEquals(0L, row.get("source_version"));
        assertEquals(eventId, row.get("last_event_id"));
        assertEquals(1, markers());
    }

    @Test
    void aLaterProductUpsertedRefreshesTheProjection() {
        ProductEvents.Product phone = product().name("Old Name").version(0);
        apply(upserted(UUID.randomUUID(), phone));
        UUID newCategory = UUID.randomUUID();

        assertEquals(Outcome.UPSERTED, apply(upserted(UUID.randomUUID(),
                phone.name("New Name").category(newCategory).price("12.34").status("INACTIVE").active(false).version(1))));

        Map<String, Object> row = row(phone.productId);
        assertEquals("New Name", row.get("name"));
        assertEquals(newCategory, row.get("category_id"));
        assertEquals("INACTIVE", row.get("status"));
        assertEquals(false, row.get("active"));
        assertEquals(1L, row.get("source_version"));
        assertEquals(1, rows());
    }

    @Test
    void aProductDeletedTombstonesTheProductAndItStopsBeingASource() {
        ProductEvents.Product phone = product().name("Doomed").version(1);
        apply(upserted(UUID.randomUUID(), phone));

        assertEquals(Outcome.DELETED, apply(deleted(UUID.randomUUID(), phone.productId, 2)));

        Map<String, Object> row = row(phone.productId);
        assertEquals(true, row.get("deleted"));
        assertEquals(2L, row.get("source_version"));
        assertNull(row.get("name"));
        assertNull(row.get("category_id"));
        assertThrows(ResourceNotFoundException.class, () -> recommendations.relatedProducts(phone.productId, 10));
    }

    // ---------- 4-5 ----------

    @Test
    void redeliveringTheSameEventHasOneEffect() {
        String record = upserted(UUID.randomUUID(), product().name("Phone"));

        assertEquals(Outcome.UPSERTED, apply(record));
        assertEquals(Outcome.DUPLICATE, apply(record));
        assertEquals(Outcome.DUPLICATE, apply(record));

        assertEquals(1, rows());
        assertEquals(1, markers());
    }

    @Test
    void concurrentDeliveriesOfTheSameEventApplyExactlyOnce() throws Exception {
        String record = upserted(UUID.randomUUID(), product().name("Phone"));

        List<String> outcomes = applyConcurrently(List.of(record, record, record, record, record, record, record, record));

        assertEquals(1, outcomes.stream().filter("UPSERTED"::equals).count(), outcomes::toString);
        assertEquals(7, outcomes.stream().filter("DUPLICATE"::equals).count(), outcomes::toString);
        assertEquals(1, rows());
        assertEquals(1, markers());
    }

    @Test
    void concurrentDifferentVersionsConvergeOnTheNewest() throws Exception {
        ProductEvents.Product phone = product();
        List<String> records = new ArrayList<>();
        for (int version = 0; version < 6; version++) {
            records.add(upserted(UUID.randomUUID(), phone.name("Phone v" + version).version(version)));
        }

        List<String> outcomes = applyConcurrently(records);

        assertTrue(outcomes.stream().allMatch(o -> o.equals("UPSERTED") || o.equals("STALE_IGNORED")), outcomes::toString);
        assertEquals("Phone v5", row(phone.productId).get("name"));
        assertEquals(5L, row(phone.productId).get("source_version"));
    }

    private List<String> applyConcurrently(List<String> records) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(records.size());
        ExecutorService pool = Executors.newFixedThreadPool(records.size());
        List<Callable<String>> attempts = new ArrayList<>();
        for (String record : records) {
            attempts.add(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                try {
                    return apply(record).name();
                } catch (RuntimeException failure) {
                    return failure.getClass().getSimpleName();
                }
            });
        }
        List<String> outcomes = new ArrayList<>();
        try {
            for (Future<String> future : pool.invokeAll(attempts)) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    // ---------- 6: stale ----------

    @Test
    void olderOrEqualVersionsAreIgnored() {
        ProductEvents.Product phone = product();
        apply(upserted(UUID.randomUUID(), phone.name("Version Two").version(2)));

        assertEquals(Outcome.STALE_IGNORED, apply(upserted(UUID.randomUUID(), phone.name("Version One").version(1))));
        assertEquals(Outcome.STALE_IGNORED, apply(upserted(UUID.randomUUID(), phone.name("Same Version").version(2))));
        assertEquals(Outcome.STALE_IGNORED, apply(deleted(UUID.randomUUID(), phone.productId, 1)));

        assertEquals("Version Two", row(phone.productId).get("name"));
        assertEquals(false, row(phone.productId).get("deleted"));
    }

    @Test
    void aTombstoneCannotBeResurrectedByAnyLaterUpsert() {
        ProductEvents.Product phone = product().name("Phone").version(3);
        String original = upserted(UUID.randomUUID(), phone);
        apply(original);
        apply(deleted(UUID.randomUUID(), phone.productId, 4));

        assertEquals(Outcome.STALE_IGNORED, apply(upserted(UUID.randomUUID(), phone.version(3))));
        assertEquals(Outcome.STALE_IGNORED, apply(upserted(UUID.randomUUID(), phone.version(99))));
        assertEquals(Outcome.DUPLICATE, apply(original));

        assertEquals(true, row(phone.productId).get("deleted"));
    }

    @Test
    void aDeleteForAnUnseenProductLeavesATombstone() {
        ProductEvents.Product phone = product().version(1);

        assertEquals(Outcome.DELETED, apply(deleted(UUID.randomUUID(), phone.productId, 2)));
        assertEquals(Outcome.STALE_IGNORED, apply(upserted(UUID.randomUUID(), phone)));
    }

    // ---------- 7-8: atomicity ----------

    @Test
    void aProjectionFailureRollsBackTheMarkerAndTheRetryIsProcessed() {
        String record = upserted(UUID.randomUUID(), product().name("BLOCKED"));
        jdbc.execute("alter table recommendation_product add constraint ck_test_block check (name is null or name <> 'BLOCKED') not valid");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> apply(record));
            assertEquals(0, markers());
            assertEquals(0, rows());
        } finally {
            jdbc.execute("alter table recommendation_product drop constraint ck_test_block");
        }

        assertEquals(Outcome.UPSERTED, apply(record));
        assertEquals(1, markers());
    }

    @Test
    void aRolledBackTransactionLeavesNoMarkerAndNoProjection() {
        String record = upserted(UUID.randomUUID(), product().name("Phone"));

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            apply(record);
            assertEquals(1, markers());
            assertEquals(1, rows());
            throw new IllegalStateException("simulated failure before commit");
        }));

        assertEquals(0, markers());
        assertEquals(0, rows());
        assertEquals(Outcome.UPSERTED, apply(record));
    }

    @Test
    void malformedAndUnsupportedRecordsWriteNothing() {
        String valid = upserted(UUID.randomUUID(), product());

        assertThrows(NonRetryableEventException.class, () -> apply("{this is not json"));
        assertThrows(NonRetryableEventException.class, () -> apply(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertThrows(NonRetryableEventException.class, () -> apply(valid.replace("\"ProductUpserted\"", "\"ProductViewed\"")));

        assertEquals(0, markers());
        assertEquals(0, rows());
    }

    // ---------- migration ----------

    @Test
    void theMigrationCreatesTablesIndexesAndConstraints() {
        assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where version = '1' and success", Integer.class));
        List<String> indexes = jdbc.queryForList("select indexname from pg_indexes where tablename = 'recommendation_product'", String.class);
        assertTrue(indexes.containsAll(List.of("recommendation_product_pkey", "idx_recommendation_candidate_category",
                "idx_recommendation_candidate_brand", "idx_recommendation_product_visibility")), indexes::toString);
        assertTrue(jdbc.queryForList("select indexname from pg_indexes where tablename = 'processed_event'", String.class)
                .contains("processed_event_pkey"));
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where table_schema = 'public' and constraint_type = 'FOREIGN KEY'", Integer.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into recommendation_product (product_id, deleted, source_version, last_event_id, indexed_at) "
                        + "values (?, false, 0, ?, now())", UUID.randomUUID(), UUID.randomUUID()));
    }
}
