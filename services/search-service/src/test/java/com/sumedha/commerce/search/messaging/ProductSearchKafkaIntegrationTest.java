package com.sumedha.commerce.search.messaging;

import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.search.config.KafkaConsumerConfig;
import com.sumedha.commerce.search.dto.response.ProductSearchResult;
import com.sumedha.commerce.search.service.ProductSearchService;
import com.sumedha.commerce.search.support.ProductEvents;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.sumedha.commerce.search.support.ProductEvents.deleted;
import static com.sumedha.commerce.search.support.ProductEvents.product;
import static com.sumedha.commerce.search.support.ProductEvents.upserted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The search consumer through a real (embedded, KRaft) broker and real PostgreSQL: product events on
 * {@code product.events.v1} become searchable, change, and disappear; records the consumer cannot
 * handle land intact on {@code product.events.v1.search.DLT} without stalling the partition.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(topics = {KafkaTopics.PRODUCT_EVENTS_V1, KafkaTopics.PRODUCT_EVENTS_V1_SEARCH_DLT}, partitions = 3)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
@ExtendWith(OutputCaptureExtension.class)
class ProductSearchKafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int TOTAL_ATTEMPTS = (int) KafkaConsumerConfig.MAX_RETRIES + 1;

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

    @Autowired private ProductSearchService searchService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;
    @Autowired private MeterRegistry meterRegistry;

    /** Spied so transient failures can be injected for one event; real otherwise. */
    @MockitoSpyBean private ProductProjectionProcessor processor;
    /** Spied only to count how often a poison record was attempted. */
    @MockitoSpyBean private ProductEventParser parser;

    private Producer<String, String> producer;
    private Consumer<String, String> deadLetterProbe;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(producerConfig, new StringSerializer(), new StringSerializer());

        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-probe-" + UUID.randomUUID());
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        deadLetterProbe = new DefaultKafkaConsumerFactory<>(
                consumerConfig, new StringDeserializer(), new StringDeserializer()).createConsumer();
        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : deadLetterProbe.partitionsFor(KafkaTopics.PRODUCT_EVENTS_V1_SEARCH_DLT)) {
            partitions.add(new TopicPartition(info.topic(), info.partition()));
        }
        deadLetterProbe.assign(partitions);
        deadLetterProbe.seekToEnd(partitions);
        deadLetterProbe.poll(Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        producer.close(Duration.ofSeconds(5));
        deadLetterProbe.close();
        jdbc.update("delete from product_search_document");
        jdbc.update("delete from processed_event");
    }

    // ---------- helpers ----------

    private void publish(UUID productId, String value, Header... headers) {
        producer.send(new ProducerRecord<>(KafkaTopics.PRODUCT_EVENTS_V1, null, productId.toString(), value, List.of(headers)));
        producer.flush();
    }

    private <T> T await(Supplier<T> attempt, String what) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            T value = attempt.get();
            if (value != null) {
                return value;
            }
            sleep(200);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private List<ProductSearchResult> search(String q) {
        return searchService.search(q, null, null, null, null, null, null, null, 0, 20).getItems();
    }

    private ProductSearchResult awaitSearchable(String q, UUID productId, String expectedName) {
        return await(() -> search(q).stream()
                .filter(r -> r.productId().equals(productId) && r.name().equals(expectedName))
                .findFirst().orElse(null), "'" + q + "' to return " + expectedName);
    }

    private void awaitVersion(UUID productId, long version) {
        await(() -> {
            List<Long> versions = jdbc.queryForList("select source_version from product_search_document where product_id = ?",
                    Long.class, productId);
            return !versions.isEmpty() && versions.getFirst() == version ? Boolean.TRUE : null;
        }, "product " + productId + " to reach version " + version);
    }

    private ConsumerRecord<String, String> awaitDeadLetter() {
        return await(() -> {
            for (ConsumerRecord<String, String> record : deadLetterProbe.poll(Duration.ofMillis(300))) {
                return record;
            }
            return null;
        }, "a record on " + KafkaTopics.PRODUCT_EVENTS_V1_SEARCH_DLT);
    }

    private void assertNoDeadLetterWithin(Duration window) {
        long until = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < until) {
            assertTrue(deadLetterProbe.poll(Duration.ofMillis(300)).isEmpty(), "nothing should have been dead-lettered");
        }
    }

    private int markerCount() {
        return jdbc.queryForObject("select count(*) from processed_event", Integer.class);
    }

    private int documentCount() {
        return jdbc.queryForObject("select count(*) from product_search_document", Integer.class);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Header traceparent(String traceId, String spanId) {
        return new RecordHeader("traceparent", ("00-" + traceId + "-" + spanId + "-01").getBytes(StandardCharsets.UTF_8));
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---------- A, B, C: lifecycle ----------

    @Test
    void aProductUpsertedRecordMakesTheProductSearchable() {
        ProductEvents.Product phone = product().name("Kafka Smart Phone").description("Great camera").version(0);
        double before = meterRegistry.get("search.projection.upserted").counter().count();

        publish(phone.productId, upserted(UUID.randomUUID(), phone));

        ProductSearchResult found = awaitSearchable("kafka smart", phone.productId, "Kafka Smart Phone");
        assertEquals(phone.sku, found.sku());
        await(() -> meterRegistry.get("search.projection.upserted").counter().count() >= before + 1 ? Boolean.TRUE : null,
                "search_projection_upserted_total to advance");
    }

    @Test
    void aSecondUpsertMakesSearchReflectTheNewContent() {
        ProductEvents.Product phone = product().name("Kafka Original Name").version(0);
        publish(phone.productId, upserted(UUID.randomUUID(), phone));
        awaitSearchable("original", phone.productId, "Kafka Original Name");

        publish(phone.productId, upserted(UUID.randomUUID(), phone.name("Kafka Renamed Device").description("Rewritten").version(1)));

        awaitSearchable("renamed", phone.productId, "Kafka Renamed Device");
        assertTrue(search("original").isEmpty(), "the old name is no longer searchable");
    }

    @Test
    void aProductDeletedRecordRemovesTheProductFromSearch() {
        ProductEvents.Product phone = product().name("Kafka Doomed Phone").version(0);
        publish(phone.productId, upserted(UUID.randomUUID(), phone));
        awaitSearchable("doomed", phone.productId, "Kafka Doomed Phone");

        publish(phone.productId, deleted(UUID.randomUUID(), phone.productId, 1));

        await(() -> search("doomed").isEmpty() ? Boolean.TRUE : null, "the deleted product to leave search");
    }

    @Test
    void anOlderStateArrivingAfterANewerOneOverTheBrokerDoesNotWin() {
        ProductEvents.Product phone = product();
        publish(phone.productId, upserted(UUID.randomUUID(), phone.name("Kafka Version Two").version(2)));
        publish(phone.productId, upserted(UUID.randomUUID(), phone.name("Kafka Version One").version(1)));
        UUID sentinel = UUID.randomUUID();
        ProductEvents.Product other = product().name("Kafka Sentinel");
        publish(phone.productId, upserted(sentinel, other));
        awaitSearchable("sentinel", other.productId, "Kafka Sentinel");

        assertEquals("Kafka Version Two", search("kafka version").getFirst().name());
    }

    // ---------- D: duplicates ----------

    @Test
    void theSameEventDeliveredThreeTimesHasOneProjectionEffect() {
        ProductEvents.Product phone = product().name("Kafka Duplicate Phone").version(0);
        String record = upserted(UUID.randomUUID(), phone);
        double duplicatesBefore = meterRegistry.get("search.duplicate.ignored").counter().count();

        publish(phone.productId, record);
        publish(phone.productId, record);
        publish(phone.productId, record);
        // Same key, same partition, strictly after the duplicates: once it is applied, they were too.
        ProductEvents.Product sentinel = product().name("Kafka After Duplicates");
        publish(phone.productId, upserted(UUID.randomUUID(), sentinel));
        awaitSearchable("after duplicates", sentinel.productId, "Kafka After Duplicates");

        assertEquals(2, documentCount());
        assertEquals(2, markerCount());
        assertEquals(duplicatesBefore + 2, meterRegistry.get("search.duplicate.ignored").counter().count());
        assertNoDeadLetterWithin(Duration.ofSeconds(1));
    }

    // ---------- E, F: unreadable records ----------

    @Test
    void aMalformedRecordIsDeadLetteredImmediatelyWithKeyValueAndTraceHeaderIntact() {
        UUID key = UUID.randomUUID();
        String traceId = randomHex(16);
        String spanId = randomHex(8);
        String poison = "{this is not json";

        publish(key, poison, traceparent(traceId, spanId));

        ConsumerRecord<String, String> dead = awaitDeadLetter();
        assertEquals(KafkaTopics.PRODUCT_EVENTS_V1_SEARCH_DLT, dead.topic());
        assertEquals(poison, dead.value());
        assertEquals(key.toString(), dead.key());
        assertEquals("00-" + traceId + "-" + spanId + "-01", header(dead, "traceparent"));
        assertEquals(KafkaTopics.PRODUCT_EVENTS_V1, header(dead, "kafka_dlt-original-topic"));
        assertEquals("search-service", header(dead, "kafka_dlt-original-consumer-group"));
        String exception = header(dead, "kafka_dlt-exception-fqcn") + " " + header(dead, "kafka_dlt-exception-cause-fqcn");
        assertTrue(exception.contains(NonRetryableEventException.class.getName()), exception);
        verify(parser, times(1)).parse(poison);
        assertEquals(0, markerCount());
        assertEquals(0, documentCount());
    }

    @Test
    void anUnsupportedSchemaVersionIsDeadLetteredWithoutRetry() {
        ProductEvents.Product phone = product().name("Kafka Future Schema");
        String future = upserted(UUID.randomUUID(), phone).replace("\"schemaVersion\":1", "\"schemaVersion\":2");

        publish(phone.productId, future);

        ConsumerRecord<String, String> dead = awaitDeadLetter();
        assertEquals(future, dead.value());
        assertEquals(phone.productId.toString(), dead.key());
        verify(parser, times(1)).parse(future);
        assertEquals(0, documentCount());
    }

    // ---------- G: poison does not stall ----------

    @Test
    void aPoisonRecordDoesNotBlockTheValidRecordBehindIt() {
        ProductEvents.Product phone = product().name("Kafka Behind Poison");

        publish(phone.productId, "{this is not json");
        publish(phone.productId, upserted(UUID.randomUUID(), phone));

        awaitSearchable("behind poison", phone.productId, "Kafka Behind Poison");
        assertEquals("{this is not json", awaitDeadLetter().value());
    }

    // ---------- H, I: transient failures ----------

    private AtomicInteger failTransientlyFor(UUID productId, int failures) {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            ProductEvent event = invocation.getArgument(0);
            if (event.productId().equals(productId) && attempts.incrementAndGet() <= failures) {
                throw new TransientDataAccessResourceException("simulated database blip");
            }
            return invocation.callRealMethod();
        }).when(processor).process(any());
        return attempts;
    }

    @Test
    void aTransientFailureIsRetriedAndThenIndexedExactlyOnce() {
        ProductEvents.Product phone = product().name("Kafka Flaky Phone").version(0);
        AtomicInteger attempts = failTransientlyFor(phone.productId, TOTAL_ATTEMPTS - 1);

        publish(phone.productId, upserted(UUID.randomUUID(), phone));

        awaitSearchable("flaky", phone.productId, "Kafka Flaky Phone");
        assertEquals(TOTAL_ATTEMPTS, attempts.get(), "two transient failures, then success on the last bounded attempt");
        assertEquals(1, markerCount());
        assertNoDeadLetterWithin(Duration.ofSeconds(3));
    }

    @Test
    void aFailureThatStaysTransientIsDeadLetteredAfterExactlyTheRetryBudget() {
        ProductEvents.Product phone = product().name("Kafka Broken Phone").version(0);
        String record = upserted(UUID.randomUUID(), phone);
        AtomicInteger attempts = failTransientlyFor(phone.productId, Integer.MAX_VALUE);

        publish(phone.productId, record);

        ConsumerRecord<String, String> dead = awaitDeadLetter();
        assertEquals(record, dead.value());
        assertEquals(phone.productId.toString(), dead.key());
        assertEquals(TOTAL_ATTEMPTS, attempts.get(), "initial delivery plus exactly two retries");
        assertEquals(0, markerCount(), "no marker is left to swallow a later replay");
        assertEquals(0, documentCount());

        sleep(2 * KafkaConsumerConfig.RETRY_INTERVAL_MS);
        assertEquals(TOTAL_ATTEMPTS, attempts.get(), "not attempted again after dead-lettering");
    }

    // ---------- tracing ----------

    @Test
    void theConsumerContinuesTheProducersTrace(CapturedOutput output) {
        ProductEvents.Product phone = product().name("Kafka Traced Phone").version(0);
        UUID eventId = UUID.randomUUID();
        String traceId = randomHex(16);
        String producerSpanId = randomHex(8);

        publish(phone.productId, upserted(eventId, phone), traceparent(traceId, producerSpanId));

        awaitSearchable("traced", phone.productId, "Kafka Traced Phone");
        String applied = await(() -> output.getOut().lines()
                .filter(line -> line.contains("Product event applied to search eventId=" + eventId))
                .findFirst().orElse(null), "the projection log line");
        assertTrue(applied.contains("traceId=" + traceId), applied);
        assertFalse(applied.contains("spanId=" + producerSpanId), "the consumer opens its own span: " + applied);
        assertTrue(applied.contains("productId=" + phone.productId), applied);
    }
}
