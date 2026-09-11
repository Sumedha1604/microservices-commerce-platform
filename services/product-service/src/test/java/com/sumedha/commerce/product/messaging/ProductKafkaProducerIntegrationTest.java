package com.sumedha.commerce.product.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.product.ProductDeletedEvent;
import com.sumedha.commerce.common.events.product.ProductUpsertedEvent;
import com.sumedha.commerce.product.dto.request.CreateCategoryRequest;
import com.sumedha.commerce.product.dto.request.CreateProductRequest;
import com.sumedha.commerce.product.dto.request.UpdateProductRequest;
import com.sumedha.commerce.product.dto.response.ProductResponse;
import com.sumedha.commerce.product.enums.ProductStatus;
import com.sumedha.commerce.product.service.CategoryService;
import com.sumedha.commerce.product.service.ProductService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Product lifecycle events through a real (embedded, KRaft) broker: what product-service actually
 * puts on {@code product.events.v1} - the explicit envelope, keyed by productId, in commit order on one
 * partition, carrying a W3C traceparent - and that nothing is published for a rolled-back change.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(topics = KafkaTopics.PRODUCT_EVENTS_V1, partitions = 3)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "product.outbox.enabled=false",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
class ProductKafkaProducerIntegrationTest {

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

    @Autowired private ProductService productService;
    @Autowired private CategoryService categoryService;
    @Autowired private ProductOutboxBatchProcessor outboxProcessor;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private Consumer<String, String> consumer;
    private UUID categoryId;

    @BeforeEach
    void setUp() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "product-producer-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : consumer.partitionsFor(KafkaTopics.PRODUCT_EVENTS_V1)) {
            partitions.add(new TopicPartition(info.topic(), info.partition()));
        }
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        consumer.poll(Duration.ofMillis(200));

        categoryId = categoryService.create(new CreateCategoryRequest("Phones", "phones-" + UUID.randomUUID(), null, null))
                .categoryId();
    }

    @AfterEach
    void tearDown() {
        consumer.close();
        jdbc.update("delete from product_outbox_event");
        jdbc.update("delete from products");
        jdbc.update("delete from categories");
    }

    private List<ConsumerRecord<String, String>> poll(int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (collected.size() < expected && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach(collected::add);
        }
        return collected;
    }

    private void drainOutbox() {
        while (outboxProcessor.publishNextBatch() > 0) {
            // one row per product per batch: keep going until nothing is claimable
        }
    }

    @Test
    void createUpdateDeletePublishesOrderedRecordsKeyedByProductIdOnOnePartition() {
        ProductResponse created = productService.create(new CreateProductRequest("SKU-K1", "Smart Phone", "smart-phone",
                categoryId, null, new BigDecimal("499.00"), "USD"));
        productService.update(created.productId(), new UpdateProductRequest("Smart Phone Pro", "smart-phone", null,
                "Better camera", categoryId, null, new BigDecimal("549.00"), "USD", ProductStatus.ACTIVE, true));
        productService.delete(created.productId());
        List<UUID> persistedEventIds = jdbc.queryForList(
                "select event_id from product_outbox_event where aggregate_id = ? order by created_at, id",
                UUID.class, created.productId());

        drainOutbox();

        List<ConsumerRecord<String, String>> records = poll(3, Duration.ofSeconds(15));
        assertEquals(3, records.size(), records::toString);
        for (ConsumerRecord<String, String> record : records) {
            assertEquals(created.productId().toString(), record.key());
            assertEquals(records.getFirst().partition(), record.partition(), "one product, one partition");
            assertNotNull(record.headers().lastHeader("traceparent"), "producer observation injects traceparent");
            assertNull(record.headers().lastHeader("__TypeId__"));
        }
        assertTrue(records.get(0).offset() < records.get(1).offset() && records.get(1).offset() < records.get(2).offset());

        EventEnvelope<ProductUpsertedEvent> first =
                JSON.readValue(records.get(0).value(), new TypeReference<EventEnvelope<ProductUpsertedEvent>>() {});
        EventEnvelope<ProductUpsertedEvent> second =
                JSON.readValue(records.get(1).value(), new TypeReference<EventEnvelope<ProductUpsertedEvent>>() {});
        EventEnvelope<ProductDeletedEvent> third =
                JSON.readValue(records.get(2).value(), new TypeReference<EventEnvelope<ProductDeletedEvent>>() {});

        assertEquals(persistedEventIds, List.of(first.eventId(), second.eventId(), third.eventId()),
                "the published eventIds are the ones minted in the business transactions");
        assertEquals("ProductUpserted", first.eventType());
        assertEquals(0L, first.payload().version());
        assertEquals("Smart Phone", first.payload().name());
        assertEquals("ProductUpserted", second.eventType());
        assertEquals(1L, second.payload().version());
        assertEquals("Smart Phone Pro", second.payload().name());
        assertEquals("ProductDeleted", third.eventType());
        assertEquals(2L, third.payload().version());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from product_outbox_event where status <> 'PUBLISHED'", Integer.class));
    }

    @Test
    void theTopicIsDeclaredWithThreePartitions() {
        assertEquals(3, consumer.partitionsFor(KafkaTopics.PRODUCT_EVENTS_V1).size());
    }

    @Test
    void aRolledBackCreatePublishesNothing() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            productService.create(new CreateProductRequest("SKU-RB", "Ghost", "ghost", categoryId, null,
                    BigDecimal.ONE, "USD"));
            status.setRollbackOnly();
        });

        drainOutbox();

        assertTrue(poll(1, Duration.ofSeconds(2)).isEmpty(), "a rolled-back change must publish nothing");
    }
}
