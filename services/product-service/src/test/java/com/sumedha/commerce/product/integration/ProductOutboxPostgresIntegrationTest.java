package com.sumedha.commerce.product.integration;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.product.ProductDeletedEvent;
import com.sumedha.commerce.common.events.product.ProductUpsertedEvent;
import com.sumedha.commerce.product.dto.request.CreateCategoryRequest;
import com.sumedha.commerce.product.dto.request.CreateProductImageRequest;
import com.sumedha.commerce.product.dto.request.CreateProductRequest;
import com.sumedha.commerce.product.dto.request.UpdateProductRequest;
import com.sumedha.commerce.product.dto.response.ProductResponse;
import com.sumedha.commerce.product.entity.Product;
import com.sumedha.commerce.product.enums.ProductStatus;
import com.sumedha.commerce.product.messaging.ProductEventPublisher;
import com.sumedha.commerce.product.messaging.ProductOutboxBatchProcessor;
import com.sumedha.commerce.product.repository.ProductRepository;
import com.sumedha.commerce.product.service.CategoryService;
import com.sumedha.commerce.product.service.ProductImageService;
import com.sumedha.commerce.product.service.ProductService;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The product outbox on real PostgreSQL: every lifecycle change and its event row commit or roll back
 * together, and the publisher only marks a row PUBLISHED after the broker acknowledged it.
 *
 * <p>No broker: the publisher is mocked so acknowledgement, failure and slowness can be controlled
 * exactly, and the scheduler is disabled so each test drives batches itself. The real Kafka path is
 * {@code ProductKafkaProducerIntegrationTest}.
 */
@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:59994",
        "product.outbox.enabled=false",
        "product.outbox.send-timeout=2s",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
@Testcontainers
class ProductOutboxPostgresIntegrationTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("product_test")
            .withUsername("product")
            .withPassword("product");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean private ProductEventPublisher publisher;

    @Autowired private ProductService productService;
    @Autowired private CategoryService categoryService;
    @Autowired private ProductImageService imageService;
    @Autowired private ProductRepository products;
    @Autowired private ProductOutboxBatchProcessor outboxProcessor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID categoryId;

    @BeforeEach
    void setUp() {
        reset(publisher);
        jdbc.update("delete from product_outbox_event");
        jdbc.update("delete from product_images");
        jdbc.update("delete from product_attributes");
        jdbc.update("delete from products");
        jdbc.update("delete from categories");
        categoryId = categoryService.create(new CreateCategoryRequest("Phones", "phones-" + UUID.randomUUID(), null, null))
                .categoryId();
    }

    // ---------- helpers ----------

    private ProductResponse createProduct(String sku) {
        return productService.create(new CreateProductRequest(sku, "Smart Phone " + sku, "phone-" + sku.toLowerCase(),
                categoryId, null, new BigDecimal("499.00"), "USD"));
    }

    private ProductResponse update(ProductResponse product, String name, String description,
                                   ProductStatus status, boolean active) {
        return productService.update(product.productId(), new UpdateProductRequest(name, product.slug(), null,
                description, categoryId, null, product.price(), product.currency(), status, active));
    }

    private List<Map<String, Object>> outboxRows(UUID productId) {
        return jdbc.queryForList("select * from product_outbox_event where aggregate_id = ? order by created_at, id",
                productId);
    }

    private Map<String, Object> singleOutboxRow() {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from product_outbox_event");
        assertEquals(1, rows.size(), rows::toString);
        return rows.getFirst();
    }

    private static EventEnvelope<ProductUpsertedEvent> upserted(Map<String, Object> row) {
        return JSON.readValue((String) row.get("payload"), new TypeReference<EventEnvelope<ProductUpsertedEvent>>() {});
    }

    private static EventEnvelope<ProductDeletedEvent> deleted(Map<String, Object> row) {
        return JSON.readValue((String) row.get("payload"), new TypeReference<EventEnvelope<ProductDeletedEvent>>() {});
    }

    private long versionOf(UUID productId) {
        return jdbc.queryForObject("select version from products where product_id = ?", Long.class, productId);
    }

    private int pendingCount() {
        return jdbc.queryForObject("select count(*) from product_outbox_event where status = 'PENDING'", Integer.class);
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, String> acknowledged() {
        SendResult<String, String> result = mock(SendResult.class);
        RecordMetadata metadata = mock(RecordMetadata.class);
        when(metadata.topic()).thenReturn("product.events.v1");
        when(metadata.partition()).thenReturn(0);
        when(metadata.offset()).thenReturn(1L);
        when(result.getRecordMetadata()).thenReturn(metadata);
        return result;
    }

    private void stubAcknowledgedSend() {
        SendResult<String, String> ack = acknowledged();
        when(publisher.publish(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(ack));
    }

    // ---------- 1-3: lifecycle changes write their event atomically ----------

    @Test
    void createPersistsTheProductAndOneProductUpsertedRowTogether() {
        ProductResponse product = createProduct("SKU-1");

        Map<String, Object> row = singleOutboxRow();
        assertEquals("ProductUpserted", row.get("event_type"));
        assertEquals("Product", row.get("aggregate_type"));
        assertEquals(product.productId(), row.get("aggregate_id"));
        assertEquals(product.productId().toString(), row.get("event_key"));
        assertEquals("product.events.v1", row.get("topic"));
        assertEquals("PENDING", row.get("status"));
        assertEquals(0, ((Number) row.get("attempt_count")).intValue());

        EventEnvelope<ProductUpsertedEvent> envelope = upserted(row);
        assertEquals(row.get("event_id"), envelope.eventId());
        assertEquals(1, envelope.schemaVersion());
        assertEquals("SKU-1", envelope.payload().sku());
        assertEquals("Smart Phone SKU-1", envelope.payload().name());
        assertEquals("DRAFT", envelope.payload().status(), "a new product starts as DRAFT");
        assertEquals(0L, envelope.payload().version());
        assertEquals(0L, versionOf(product.productId()));
        assertEquals(0, new BigDecimal("499.00").compareTo(envelope.payload().price()));
    }

    @Test
    void updatePersistsAProductUpsertedWithTheNewContentAndTheNextVersion() {
        ProductResponse product = createProduct("SKU-2");

        update(product, "Smart Phone Pro", "Now with a better camera", ProductStatus.ACTIVE, true);

        List<Map<String, Object>> rows = outboxRows(product.productId());
        assertEquals(2, rows.size());
        EventEnvelope<ProductUpsertedEvent> second = upserted(rows.get(1));
        assertEquals("Smart Phone Pro", second.payload().name());
        assertEquals("Now with a better camera", second.payload().description());
        assertEquals("ACTIVE", second.payload().status());
        assertEquals(1L, second.payload().version());
        assertEquals(1L, versionOf(product.productId()));
        assertTrue(second.payload().updatedAt().isAfter(upserted(rows.get(0)).payload().updatedAt())
                || second.payload().updatedAt().equals(upserted(rows.get(0)).payload().updatedAt()));
    }

    @Test
    void anUpdateThatChangesNothingWritesNoEvent() {
        ProductResponse product = createProduct("SKU-3");

        productService.update(product.productId(), new UpdateProductRequest(product.name(), product.slug(),
                product.shortDescription(), product.description(), categoryId, null, product.price(),
                product.currency(), product.status(), product.active()));

        assertEquals(1, outboxRows(product.productId()).size(), "only the create event");
        assertEquals(0L, versionOf(product.productId()));
    }

    @Test
    void deactivationIsAnUpsertCarryingTheNewStatusAndActiveFlag() {
        ProductResponse product = createProduct("SKU-4");
        ProductResponse active = update(product, product.name(), null, ProductStatus.ACTIVE, true);

        update(active, active.name(), null, ProductStatus.INACTIVE, false);

        List<Map<String, Object>> rows = outboxRows(product.productId());
        assertEquals(3, rows.size());
        EventEnvelope<ProductUpsertedEvent> last = upserted(rows.get(2));
        assertEquals("ProductUpserted", rows.get(2).get("event_type"));
        assertEquals("INACTIVE", last.payload().status());
        assertFalse(last.payload().active());
        assertEquals(2L, last.payload().version());
    }

    @Test
    void deletePersistsAProductDeletedOrderedAfterTheLastVersionAndRemovesTheProduct() {
        ProductResponse product = createProduct("SKU-5");
        update(product, "Renamed", null, ProductStatus.ACTIVE, true);

        productService.delete(product.productId());

        assertFalse(products.existsById(product.productId()));
        List<Map<String, Object>> rows = outboxRows(product.productId());
        assertEquals(3, rows.size());
        assertEquals("ProductDeleted", rows.get(2).get("event_type"));
        assertEquals(product.productId().toString(), rows.get(2).get("event_key"));
        assertEquals(2L, deleted(rows.get(2)).payload().version(), "last committed version 1, deletion is 2");
    }

    @Test
    void aDeleteRefusedBecauseOfImagesWritesNoEventAndKeepsTheProduct() {
        ProductResponse product = createProduct("SKU-6");
        imageService.create(product.productId(), new CreateProductImageRequest("http://img/1.png", null, 0, true));

        assertThrows(com.sumedha.commerce.common.core.exception.ConflictException.class,
                () -> productService.delete(product.productId()));

        assertTrue(products.existsById(product.productId()));
        assertEquals(1, outboxRows(product.productId()).size());
    }

    // ---------- 4: rollback removes both ----------

    @Test
    void anEnclosingRollbackRemovesTheProductAndItsEventTogether() {
        String sku = "SKU-ROLLBACK";

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            createProduct(sku);
            assertEquals(1, jdbc.queryForObject("select count(*) from product_outbox_event", Integer.class),
                    "the event row is written inside the business transaction");
            status.setRollbackOnly();
        });

        assertFalse(products.existsBySku(sku));
        assertEquals(0, jdbc.queryForObject("select count(*) from product_outbox_event", Integer.class));
    }

    /** A failing outbox insert - rejected by PostgreSQL itself - must take the product change down with it. */
    @Test
    void anOutboxInsertFailureRollsBackTheProductChange() {
        ProductResponse product = createProduct("SKU-7");
        jdbc.execute("alter table product_outbox_event add constraint ck_test_block_upserts "
                + "check (event_type <> 'ProductUpserted') not valid");
        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> update(product, "Must Not Stick", null, ProductStatus.ACTIVE, true));
            assertThrows(DataIntegrityViolationException.class, () -> createProduct("SKU-8"));
        } finally {
            jdbc.execute("alter table product_outbox_event drop constraint ck_test_block_upserts");
        }

        Product unchanged = products.findById(product.productId()).orElseThrow();
        assertEquals("Smart Phone SKU-7", unchanged.getName());
        assertEquals(ProductStatus.DRAFT, unchanged.getStatus());
        assertEquals(0L, unchanged.getVersion());
        assertFalse(products.existsBySku("SKU-8"));
        assertEquals(1, jdbc.queryForObject("select count(*) from product_outbox_event", Integer.class));
    }

    // ---------- version monotonicity under concurrency ----------

    /**
     * Two writers read the same version and both try to update. Exactly one commits; the other is
     * refused rather than silently overwriting - so every committed state has its own version and
     * exactly one event.
     */
    @Test
    void concurrentUpdatesOfOneProductCommitExactlyOneVersionAndOneEvent() throws Exception {
        ProductResponse product = createProduct("SKU-RACE");
        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> outcomes = new ArrayList<>();
        try {
            for (String name : List.of("Writer A", "Writer B")) {
                outcomes.add(pool.submit(() -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            products.findById(product.productId()).orElseThrow();
                            await(bothLoaded);
                            update(product, name, null, ProductStatus.ACTIVE, true);
                        });
                        return "COMMITTED";
                    } catch (ObjectOptimisticLockingFailureException conflict) {
                        return "CONFLICT";
                    }
                }));
            }
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, results.stream().filter("COMMITTED"::equals).count(), results::toString);
            assertEquals(1, results.stream().filter("CONFLICT"::equals).count(), results::toString);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1L, versionOf(product.productId()));
        List<Map<String, Object>> rows = outboxRows(product.productId());
        assertEquals(2, rows.size(), "the create plus exactly one committed update");
        assertEquals(1L, upserted(rows.get(1)).payload().version());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 5-8: the publisher ----------

    @Test
    void anAcknowledgedPublishMarksTheRowPublished() {
        createProduct("SKU-PUB");
        stubAcknowledgedSend();

        assertEquals(1, outboxProcessor.publishNextBatch());

        Map<String, Object> row = singleOutboxRow();
        assertEquals("PUBLISHED", row.get("status"));
        assertNotNull(row.get("published_at"));
        verify(publisher).publish("product.events.v1", (String) row.get("event_key"), (String) row.get("payload"));
    }

    @Test
    void aRowStaysPendingUntilTheBrokerAcknowledges() throws Exception {
        createProduct("SKU-ACK");
        CompletableFuture<SendResult<String, String>> acknowledgement = new CompletableFuture<>();
        CountDownLatch sendStarted = new CountDownLatch(1);
        when(publisher.publish(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            sendStarted.countDown();
            return acknowledgement;
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> batch = executor.submit(outboxProcessor::publishNextBatch);
            assertTrue(sendStarted.await(5, TimeUnit.SECONDS));
            assertEquals("PENDING", singleOutboxRow().get("status"), "sent but not acknowledged is not published");

            acknowledgement.complete(acknowledged());
            assertEquals(1, batch.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals("PUBLISHED", singleOutboxRow().get("status"));
    }

    @Test
    void anUnacknowledgedSendTimesOutAndStaysPendingWithBackoff() {
        createProduct("SKU-SLOW");
        when(publisher.publish(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());

        outboxProcessor.publishNextBatch();

        Map<String, Object> row = singleOutboxRow();
        assertEquals("PENDING", row.get("status"));
        assertEquals(1, ((Number) row.get("attempt_count")).intValue());
        assertTrue(((String) row.get("last_error")).contains("TimeoutException"), (String) row.get("last_error"));
        assertNotNull(row.get("next_attempt_at"));
    }

    @Test
    void aFailedPublishIsRetriedLaterWithTheSameEventIdAndPayload() {
        createProduct("SKU-RETRY");
        Map<String, Object> original = singleOutboxRow();
        when(publisher.publish(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        outboxProcessor.publishNextBatch();

        Map<String, Object> failed = singleOutboxRow();
        assertEquals("PENDING", failed.get("status"));
        assertEquals(1, ((Number) failed.get("attempt_count")).intValue());
        assertTrue(((String) failed.get("last_error")).contains("broker unavailable"));
        assertEquals(0, outboxProcessor.publishNextBatch(), "backoff has not elapsed, so nothing is claimable");

        jdbc.update("update product_outbox_event set next_attempt_at = now()");
        stubAcknowledgedSend();
        assertEquals(1, outboxProcessor.publishNextBatch());

        Map<String, Object> recovered = singleOutboxRow();
        assertEquals("PUBLISHED", recovered.get("status"));
        assertEquals(original.get("event_id"), recovered.get("event_id"));
        assertEquals(original.get("payload"), recovered.get("payload"));
        assertNull(recovered.get("last_error"));
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(2)).publish(anyString(), anyString(), payloads.capture());
        assertEquals(payloads.getAllValues().get(0), payloads.getAllValues().get(1));
    }

    @Test
    void aCrashAfterTheAcknowledgementRepublishesTheIdenticalRecord() {
        createProduct("SKU-CRASH");
        stubAcknowledgedSend();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outboxProcessor.publishNextBatch();
            status.setRollbackOnly();
        });
        assertEquals("PENDING", singleOutboxRow().get("status"), "the PUBLISHED update was lost with the crash");
        outboxProcessor.publishNextBatch();

        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(2)).publish(anyString(), anyString(), payloads.capture());
        assertEquals(payloads.getAllValues().get(0), payloads.getAllValues().get(1),
                "same eventId and bytes - consumers deduplicate, this is not exactly-once");
    }

    @Test
    void publishedRowsAreNotRepublishedAndTheBatchIsBounded() {
        for (int i = 0; i < 21; i++) {
            createProduct("SKU-BATCH-" + i);
        }
        stubAcknowledgedSend();

        assertEquals(20, outboxProcessor.publishNextBatch());
        assertEquals(1, pendingCount());
        assertEquals(1, outboxProcessor.publishNextBatch());
        assertEquals(0, outboxProcessor.publishNextBatch());
        verify(publisher, times(21)).publish(anyString(), anyString(), anyString());
    }

    @Test
    void twoConcurrentPublishersNeverClaimTheSameRow() throws Exception {
        createProduct("SKU-LOCK");
        CompletableFuture<SendResult<String, String>> acknowledgement = new CompletableFuture<>();
        CountDownLatch sendStarted = new CountDownLatch(1);
        when(publisher.publish(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            sendStarted.countDown();
            return acknowledgement;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(outboxProcessor::publishNextBatch);
            assertTrue(sendStarted.await(5, TimeUnit.SECONDS));
            Future<Integer> second = executor.submit(outboxProcessor::publishNextBatch);
            assertEquals(0, second.get(5, TimeUnit.SECONDS), "the claimed row is skipped, not waited on or duplicated");
            acknowledgement.complete(acknowledged());
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        verify(publisher, times(1)).publish(anyString(), anyString(), anyString());
    }

    // ---------- 9: per-product ordering ----------

    @Test
    void aProductsLaterEventWaitsWhileItsEarlierEventIsInBackoff() {
        ProductResponse product = createProduct("SKU-ORDER");
        update(product, "Second State", null, ProductStatus.ACTIVE, true);
        jdbc.update("update product_outbox_event set attempt_count = 1, last_error = 'forced', "
                + "next_attempt_at = now() + interval '5 minutes' where aggregate_id = ? and event_type = 'ProductUpserted' "
                + "and (payload like '%\"version\":0%')", product.productId());
        stubAcknowledgedSend();

        assertEquals(0, outboxProcessor.publishNextBatch());

        verify(publisher, never()).publish(anyString(), anyString(), anyString());
        assertEquals(2, pendingCount(), "version 1 must not overtake version 0");
    }

    @Test
    void oneBatchClaimsAProductsEventsOneAtATimeInOrder() {
        ProductResponse product = createProduct("SKU-SEQ");
        ProductResponse renamed = update(product, "Second State", null, ProductStatus.ACTIVE, true);
        productService.delete(renamed.productId());
        stubAcknowledgedSend();

        assertEquals(1, outboxProcessor.publishNextBatch());
        assertEquals(1, outboxProcessor.publishNextBatch());
        assertEquals(1, outboxProcessor.publishNextBatch());

        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(3)).publish(anyString(), anyString(), payloads.capture());
        assertTrue(payloads.getAllValues().get(0).contains("\"version\":0"));
        assertTrue(payloads.getAllValues().get(1).contains("\"version\":1"));
        assertTrue(payloads.getAllValues().get(2).contains("\"eventType\":\"ProductDeleted\""));
    }

    @Test
    void aProductInBackoffDoesNotHoldBackADifferentProduct() {
        ProductResponse blocked = createProduct("SKU-BLOCKED");
        jdbc.update("update product_outbox_event set attempt_count = 1, next_attempt_at = now() + interval '5 minutes' "
                + "where aggregate_id = ?", blocked.productId());
        ProductResponse independent = createProduct("SKU-FREE");
        stubAcknowledgedSend();

        assertEquals(1, outboxProcessor.publishNextBatch());

        assertEquals("PUBLISHED", outboxRows(independent.productId()).getFirst().get("status"));
        assertEquals("PENDING", outboxRows(blocked.productId()).getFirst().get("status"));
    }
}
