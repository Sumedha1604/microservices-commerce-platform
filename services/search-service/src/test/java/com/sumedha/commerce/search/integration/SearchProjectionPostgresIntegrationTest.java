package com.sumedha.commerce.search.integration;

import com.sumedha.commerce.search.messaging.NonRetryableEventException;
import com.sumedha.commerce.search.messaging.ProductEventParser;
import com.sumedha.commerce.search.messaging.ProductProjectionProcessor;
import com.sumedha.commerce.search.messaging.ProductProjectionProcessor.Outcome;
import com.sumedha.commerce.search.service.ProductSearchService;
import com.sumedha.commerce.search.support.ProductEvents;
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

import static com.sumedha.commerce.search.support.ProductEvents.deleted;
import static com.sumedha.commerce.search.support.ProductEvents.product;
import static com.sumedha.commerce.search.support.ProductEvents.upserted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search projection on real PostgreSQL: what one product event writes, and what a duplicate,
 * concurrent, stale, failed or rolled-back delivery does not write.
 *
 * <p>The listener is parked and the bootstrap address is a dead port; broker behaviour is
 * {@code ProductSearchKafkaIntegrationTest}.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:59993",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
@Testcontainers
class SearchProjectionPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("search_test")
            .withUsername("search")
            .withPassword("search");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ProductEventParser parser;
    @Autowired private ProductProjectionProcessor processor;
    @Autowired private ProductSearchService searchService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        jdbc.update("delete from product_search_document");
        jdbc.update("delete from processed_event");
    }

    private Outcome apply(String record) {
        return processor.process(parser.parse(record));
    }

    private Map<String, Object> document(UUID productId) {
        return jdbc.queryForMap("select * from product_search_document where product_id = ?", productId);
    }

    private int markerCount() {
        return jdbc.queryForObject("select count(*) from processed_event", Integer.class);
    }

    private int documentCount() {
        return jdbc.queryForObject("select count(*) from product_search_document", Integer.class);
    }

    private List<UUID> search(String q) {
        return searchService.search(q, null, null, null, null, null, null, null, 0, 20).getItems().stream()
                .map(result -> result.productId()).toList();
    }

    // ---------- 1-3: upsert, update, delete ----------

    @Test
    void aProductUpsertedInsertsTheSearchableProjection() {
        ProductEvents.Product phone = product().name("Smart Phone X").sku("PH-100")
                .shortDescription("A phone").description("Flagship smartphone").price("999.99").version(0);
        UUID eventId = UUID.randomUUID();

        assertEquals(Outcome.UPSERTED, apply(upserted(eventId, phone)));

        Map<String, Object> row = document(phone.productId);
        assertEquals("Smart Phone X", row.get("name"));
        assertEquals("PH-100", row.get("sku"));
        assertEquals("Flagship smartphone", row.get("description"));
        assertEquals(0, new java.math.BigDecimal("999.99").compareTo((java.math.BigDecimal) row.get("price")));
        assertEquals("ACTIVE", row.get("status"));
        assertEquals(false, row.get("deleted"));
        assertEquals(0L, row.get("source_version"));
        assertEquals(eventId, row.get("last_event_id"));
        assertNotNull(row.get("indexed_at"));
        assertNotNull(row.get("search_vector"), "the generated tsvector is maintained by PostgreSQL");
        assertTrue(((String) row.get("search_text")).contains("smart phone x"), "lower-cased haystack");
        assertEquals(List.of(phone.productId), search("smart phone"));
        assertEquals(1, markerCount());
    }

    @Test
    void aLaterProductUpsertedReplacesTheSearchableContent() {
        ProductEvents.Product phone = product().name("Smart Phone X").description("Original text").version(0);
        apply(upserted(UUID.randomUUID(), phone));

        assertEquals(Outcome.UPSERTED, apply(upserted(UUID.randomUUID(),
                phone.name("Pocket Communicator").description("Renamed and rewritten").price("799.00").version(1))));

        Map<String, Object> row = document(phone.productId);
        assertEquals("Pocket Communicator", row.get("name"));
        assertEquals("Renamed and rewritten", row.get("description"));
        assertEquals(1L, row.get("source_version"));
        assertEquals(List.of(phone.productId), search("communicator"));
        assertEquals(List.of(), search("original"), "the old content is no longer searchable");
        assertEquals(1, documentCount());
    }

    @Test
    void aProductDeletedRemovesTheProductFromSearchAsATombstone() {
        ProductEvents.Product phone = product().name("Smart Phone X").description("Flagship").version(1);
        apply(upserted(UUID.randomUUID(), phone));

        assertEquals(Outcome.DELETED, apply(deleted(UUID.randomUUID(), phone.productId, 2)));

        Map<String, Object> row = document(phone.productId);
        assertEquals(true, row.get("deleted"));
        assertEquals(2L, row.get("source_version"));
        assertNull(row.get("name"), "catalogue data is cleared, not just hidden");
        assertNull(row.get("price"));
        assertEquals(List.of(), search("smart phone"));
    }

    @Test
    void anInactiveOrNonActiveStatusProductIsNotReturnedByDefault() {
        ProductEvents.Product inactiveFlag = product().name("Hidden Phone").active(false);
        ProductEvents.Product draft = product().name("Draft Phone").status("DRAFT");
        apply(upserted(UUID.randomUUID(), inactiveFlag));
        apply(upserted(UUID.randomUUID(), draft));

        assertEquals(List.of(), search("phone"));
        assertEquals(2, documentCount(), "both are indexed, neither is visible");
    }

    // ---------- 4-5: duplicates ----------

    @Test
    void redeliveringTheSameEventHasOneEffect() {
        ProductEvents.Product phone = product().name("Smart Phone X").version(0);
        String record = upserted(UUID.randomUUID(), phone);

        assertEquals(Outcome.UPSERTED, apply(record));
        assertEquals(Outcome.DUPLICATE, apply(record));
        assertEquals(Outcome.DUPLICATE, apply(record));

        assertEquals(1, documentCount());
        assertEquals(1, markerCount());
        assertEquals(0L, document(phone.productId).get("source_version"));
    }

    @Test
    void concurrentDeliveriesOfTheSameEventApplyExactlyOnce() throws Exception {
        String record = upserted(UUID.randomUUID(), product().name("Smart Phone X"));

        List<String> outcomes = applyConcurrently(List.of(record, record, record, record, record, record, record, record));

        assertEquals(1, outcomes.stream().filter("UPSERTED"::equals).count(), outcomes::toString);
        assertEquals(7, outcomes.stream().filter("DUPLICATE"::equals).count(), outcomes::toString);
        assertEquals(1, documentCount());
        assertEquals(1, markerCount());
    }

    /**
     * Different versions of one product racing each other (e.g. a replay overlapping live traffic):
     * no errors, and the newest version wins however the threads interleave.
     */
    @Test
    void concurrentDifferentVersionsOfOneProductConvergeOnTheNewest() throws Exception {
        ProductEvents.Product phone = product();
        List<String> records = new ArrayList<>();
        for (int version = 0; version < 6; version++) {
            records.add(upserted(UUID.randomUUID(), phone.name("Phone v" + version).version(version)));
        }

        List<String> outcomes = applyConcurrently(records);

        assertTrue(outcomes.stream().allMatch(o -> o.equals("UPSERTED") || o.equals("STALE_IGNORED")), outcomes::toString);
        assertEquals("Phone v5", document(phone.productId).get("name"));
        assertEquals(5L, document(phone.productId).get("source_version"));
        assertEquals(6, markerCount(), "every distinct event is recorded as processed");
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

    // ---------- 6-7: atomicity and rollback ----------

    /** A projection write PostgreSQL rejects must take the processed marker down with it. */
    @Test
    void aProjectionFailureRollsBackTheMarkerAndTheRetryIsProcessed() {
        ProductEvents.Product phone = product().name("BLOCKED").version(0);
        String record = upserted(UUID.randomUUID(), phone);
        jdbc.execute("alter table product_search_document add constraint ck_test_block check (name is null or name <> 'BLOCKED')");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> apply(record));
            assertEquals(0, markerCount(), "no marker survives a failed projection");
            assertEquals(0, documentCount());
        } finally {
            jdbc.execute("alter table product_search_document drop constraint ck_test_block");
        }

        assertEquals(Outcome.UPSERTED, apply(record), "the redelivery is processed, not swallowed as a duplicate");
        assertEquals(1, markerCount());
    }

    @Test
    void aRolledBackTransactionLeavesNeitherMarkerNorProjection() {
        ProductEvents.Product phone = product().name("Smart Phone X");
        String record = upserted(UUID.randomUUID(), phone);

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            apply(record);
            assertEquals(1, markerCount(), "claimed inside the transaction");
            assertEquals(1, documentCount(), "and projected inside it");
            throw new IllegalStateException("simulated failure before commit");
        }));

        assertEquals(0, markerCount());
        assertEquals(0, documentCount());
        assertEquals(Outcome.UPSERTED, apply(record));
    }

    // ---------- 11: stale events ----------

    @Test
    void anOlderUpsertArrivingLateChangesNothing() {
        ProductEvents.Product phone = product();
        apply(upserted(UUID.randomUUID(), phone.name("Version Two").version(2)));

        assertEquals(Outcome.STALE_IGNORED, apply(upserted(UUID.randomUUID(), phone.name("Version One").version(1))));
        assertEquals(Outcome.STALE_IGNORED, apply(upserted(UUID.randomUUID(), phone.name("Same Version").version(2))));

        assertEquals("Version Two", document(phone.productId).get("name"));
        assertEquals(3, markerCount(), "stale events are still recorded as processed");
    }

    @Test
    void aReplayedUpsertAfterTheDeleteCannotResurrectTheProduct() {
        ProductEvents.Product phone = product().name("Smart Phone X").version(3);
        String oldUpsert = upserted(UUID.randomUUID(), phone);
        apply(oldUpsert);
        apply(deleted(UUID.randomUUID(), phone.productId, 4));

        assertEquals(Outcome.STALE_IGNORED, apply(upserted(UUID.randomUUID(), phone.version(3))));
        assertEquals(Outcome.STALE_IGNORED, apply(upserted(UUID.randomUUID(), phone.version(9))),
                "a deleted product is final - product ids are never reused");
        assertEquals(Outcome.DUPLICATE, apply(oldUpsert));

        assertEquals(true, document(phone.productId).get("deleted"));
        assertEquals(List.of(), search("smart phone"));
    }

    @Test
    void aDeleteForAProductNeverSeenLeavesATombstoneThatBlocksALateUpsert() {
        ProductEvents.Product phone = product().name("Smart Phone X").version(1);

        assertEquals(Outcome.DELETED, apply(deleted(UUID.randomUUID(), phone.productId, 2)));
        assertEquals(Outcome.STALE_IGNORED, apply(upserted(UUID.randomUUID(), phone)));

        assertEquals(List.of(), search("smart phone"));
    }

    @Test
    void anOlderDeleteArrivingAfterANewerStateIsStale() {
        ProductEvents.Product phone = product().name("Smart Phone X").version(5);
        apply(upserted(UUID.randomUUID(), phone));

        assertEquals(Outcome.STALE_IGNORED, apply(deleted(UUID.randomUUID(), phone.productId, 3)));

        assertEquals(false, document(phone.productId).get("deleted"));
    }

    // ---------- unreadable records ----------

    @Test
    void malformedAndUnsupportedRecordsWriteNothing() {
        String valid = upserted(UUID.randomUUID(), product().name("Smart Phone X"));

        assertThrows(NonRetryableEventException.class, () -> apply("{this is not json"));
        assertThrows(NonRetryableEventException.class, () -> apply(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertThrows(NonRetryableEventException.class, () -> apply(valid.replace("\"ProductUpserted\"", "\"ProductArchived\"")));

        assertEquals(0, markerCount());
        assertEquals(0, documentCount());
    }

    // ---------- 8: migration ----------

    @Test
    void theMigrationCreatesTheSchemaExtensionAndIndexes() {
        assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where version = '1' and success", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pg_extension where extname = 'pg_trgm'", Integer.class));

        List<String> indexes = jdbc.queryForList("select indexname from pg_indexes where tablename = 'product_search_document'", String.class);
        assertTrue(indexes.containsAll(List.of("product_search_document_pkey", "idx_product_search_vector",
                "idx_product_search_text_trgm", "idx_product_search_visible", "idx_product_search_category",
                "idx_product_search_brand")), indexes::toString);
        assertTrue(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'idx_product_search_text_trgm'", String.class)
                .contains("gin_trgm_ops"));
        assertTrue(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'idx_product_search_vector'", String.class)
                .toLowerCase().contains("using gin"));

        assertEquals(2, jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'product_search_document' "
                + "and column_name in ('search_vector', 'search_text') and is_generated = 'ALWAYS'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where table_schema = 'public' and constraint_type = 'FOREIGN KEY'", Integer.class));
    }

    @Test
    void theDatabaseRefusesALiveDocumentWithoutItsCatalogueFields() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into product_search_document (product_id, deleted, source_version, last_event_id, indexed_at) "
                        + "values (?, false, 0, ?, now())", UUID.randomUUID(), UUID.randomUUID()));
        assertFalse(jdbc.queryForList("select 1 from product_search_document").iterator().hasNext());
    }
}
