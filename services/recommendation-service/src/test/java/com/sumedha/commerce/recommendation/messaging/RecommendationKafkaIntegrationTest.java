package com.sumedha.commerce.recommendation.messaging;

import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.recommendation.config.KafkaConsumerConfig;
import com.sumedha.commerce.recommendation.dto.response.ProductRecommendation;
import com.sumedha.commerce.recommendation.service.RecommendationService;
import com.sumedha.commerce.recommendation.support.ProductEvents;
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

import static com.sumedha.commerce.recommendation.support.ProductEvents.deleted;
import static com.sumedha.commerce.recommendation.support.ProductEvents.product;
import static com.sumedha.commerce.recommendation.support.ProductEvents.upserted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The recommendation consumer through a real (embedded, KRaft) broker and real PostgreSQL: product
 * events become recommendations, change them, and disappear; records the consumer cannot handle land
 * intact on {@code product.events.v1.recommendation.DLT} without stalling the partition.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(topics = {KafkaTopics.PRODUCT_EVENTS_V1, KafkaTopics.PRODUCT_EVENTS_V1_RECOMMENDATION_DLT}, partitions = 3)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
@ExtendWith(OutputCaptureExtension.class)
class RecommendationKafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int TOTAL_ATTEMPTS = (int) KafkaConsumerConfig.MAX_RETRIES + 1;

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

    @Autowired private RecommendationService recommendations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;
    @Autowired private MeterRegistry meterRegistry;

    @MockitoSpyBean private ProductProjectionProcessor processor;
    @MockitoSpyBean private ProductEventParser parser;

    private Producer<String, String> producer;
    private Consumer<String, String> deadLetterProbe;
    private final UUID category = UUID.randomUUID();
    private final UUID brand = UUID.randomUUID();

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
        for (PartitionInfo info : deadLetterProbe.partitionsFor(KafkaTopics.PRODUCT_EVENTS_V1_RECOMMENDATION_DLT)) {
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
        jdbc.update("delete from recommendation_product");
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

    private String nameOf(UUID productId) {
        List<String> names = jdbc.queryForList("select coalesce(name, '<tombstone>') from recommendation_product where product_id = ?",
                String.class, productId);
        return names.isEmpty() ? null : names.getFirst();
    }

    private void awaitName(UUID productId, String expected) {
        await(() -> expected.equals(nameOf(productId)) ? Boolean.TRUE : null, productId + " to be named " + expected);
    }

    private List<UUID> relatedIds(UUID sourceId) {
        try {
            return recommendations.relatedProducts(sourceId, 50).items().stream().map(ProductRecommendation::productId).toList();
        } catch (ResourceNotFoundException notYetIndexed) {
            return null;
        }
    }

    private ConsumerRecord<String, String> awaitDeadLetter() {
        return await(() -> {
            for (ConsumerRecord<String, String> record : deadLetterProbe.poll(Duration.ofMillis(300))) {
                return record;
            }
            return null;
        }, "a record on " + KafkaTopics.PRODUCT_EVENTS_V1_RECOMMENDATION_DLT);
    }

    private void assertNoDeadLetterWithin(Duration window) {
        long until = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < until) {
            assertTrue(deadLetterProbe.poll(Duration.ofMillis(300)).isEmpty(), "nothing should have been dead-lettered");
        }
    }

    private int markers() {
        return jdbc.queryForObject("select count(*) from processed_event", Integer.class);
    }

    private int rows() {
        return jdbc.queryForObject("select count(*) from recommendation_product", Integer.class);
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

    // ---------- A, B, C ----------

    @Test
    void productUpsertedRecordsBecomeRankedRecommendations() {
        ProductEvents.Product source = product().name("Kafka Source").category(category).brand(brand).price("100.00");
        ProductEvents.Product categoryOnly = product().name("Kafka Category Only").category(category).price("900.00");
        ProductEvents.Product categoryAndBrand = product().name("Kafka Category And Brand").category(category).brand(brand).price("105.00");
        double before = meterRegistry.get("recommendation.projection.upserted").counter().count();

        publish(source.productId, upserted(UUID.randomUUID(), source));
        publish(categoryOnly.productId, upserted(UUID.randomUUID(), categoryOnly));
        publish(categoryAndBrand.productId, upserted(UUID.randomUUID(), categoryAndBrand));

        List<UUID> expected = List.of(categoryAndBrand.productId, categoryOnly.productId);
        await(() -> expected.equals(relatedIds(source.productId)) ? Boolean.TRUE : null, "ranked recommendations");
        assertEquals(before + 3, meterRegistry.get("recommendation.projection.upserted").counter().count());
    }

    @Test
    void anUpdateRefreshesTheProjectionAndTheRanking() {
        ProductEvents.Product source = product().name("Kafka Source").category(category).price("100.00");
        ProductEvents.Product candidate = product().name("Kafka Candidate").category(category).price("100.00").version(0);
        publish(source.productId, upserted(UUID.randomUUID(), source));
        publish(candidate.productId, upserted(UUID.randomUUID(), candidate));
        await(() -> List.of(candidate.productId).equals(relatedIds(source.productId)) ? Boolean.TRUE : null, "candidate recommended");

        publish(candidate.productId, upserted(UUID.randomUUID(), candidate.name("Kafka Renamed").category(UUID.randomUUID()).version(1)));

        awaitName(candidate.productId, "Kafka Renamed");
        assertEquals(List.of(), relatedIds(source.productId), "moving it to another category removes the relation");
    }

    @Test
    void aDeleteHidesTheProduct() {
        ProductEvents.Product source = product().name("Kafka Source").category(category);
        ProductEvents.Product candidate = product().name("Kafka Doomed").category(category).version(0);
        publish(source.productId, upserted(UUID.randomUUID(), source));
        publish(candidate.productId, upserted(UUID.randomUUID(), candidate));
        await(() -> List.of(candidate.productId).equals(relatedIds(source.productId)) ? Boolean.TRUE : null, "candidate recommended");

        publish(candidate.productId, deleted(UUID.randomUUID(), candidate.productId, 1));

        await(() -> List.of().equals(relatedIds(source.productId)) ? Boolean.TRUE : null, "deleted candidate removed");
        assertEquals("<tombstone>", nameOf(candidate.productId));
    }

    // ---------- D, E ----------

    @Test
    void theSameEventThreeTimesHasOneEffect() {
        ProductEvents.Product phone = product().name("Kafka Duplicate");
        String record = upserted(UUID.randomUUID(), phone);
        double duplicatesBefore = meterRegistry.get("recommendation.duplicate.ignored").counter().count();

        publish(phone.productId, record);
        publish(phone.productId, record);
        publish(phone.productId, record);
        ProductEvents.Product sentinel = product().name("Kafka Sentinel");
        publish(phone.productId, upserted(UUID.randomUUID(), sentinel));
        awaitName(sentinel.productId, "Kafka Sentinel");

        assertEquals(2, rows());
        assertEquals(2, markers());
        assertEquals(duplicatesBefore + 2, meterRegistry.get("recommendation.duplicate.ignored").counter().count());
    }

    @Test
    void anOlderVersionArrivingLaterIsIgnored() {
        ProductEvents.Product phone = product();
        double staleBefore = meterRegistry.get("recommendation.stale.ignored").counter().count();

        publish(phone.productId, upserted(UUID.randomUUID(), phone.name("Kafka Version Two").version(2)));
        publish(phone.productId, upserted(UUID.randomUUID(), phone.name("Kafka Version One").version(1)));
        ProductEvents.Product sentinel = product().name("Kafka Stale Sentinel");
        publish(phone.productId, upserted(UUID.randomUUID(), sentinel));
        awaitName(sentinel.productId, "Kafka Stale Sentinel");

        assertEquals("Kafka Version Two", nameOf(phone.productId));
        assertEquals(staleBefore + 1, meterRegistry.get("recommendation.stale.ignored").counter().count());
    }

    // ---------- F, G ----------

    @Test
    void aMalformedRecordIsDeadLetteredImmediatelyWithKeyValueAndTraceHeaderIntact() {
        UUID key = UUID.randomUUID();
        String traceId = randomHex(16);
        String spanId = randomHex(8);
        String poison = "{this is not json";

        publish(key, poison, traceparent(traceId, spanId));

        ConsumerRecord<String, String> dead = awaitDeadLetter();
        assertEquals(KafkaTopics.PRODUCT_EVENTS_V1_RECOMMENDATION_DLT, dead.topic());
        assertEquals(poison, dead.value());
        assertEquals(key.toString(), dead.key());
        assertEquals("00-" + traceId + "-" + spanId + "-01", header(dead, "traceparent"));
        assertEquals(KafkaTopics.PRODUCT_EVENTS_V1, header(dead, "kafka_dlt-original-topic"));
        assertEquals("recommendation-service", header(dead, "kafka_dlt-original-consumer-group"));
        String exception = header(dead, "kafka_dlt-exception-fqcn") + " " + header(dead, "kafka_dlt-exception-cause-fqcn");
        assertTrue(exception.contains(NonRetryableEventException.class.getName()), exception);
        verify(parser, times(1)).parse(poison);
        assertEquals(0, markers());
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
        assertEquals(0, rows());
    }

    // ---------- H ----------

    @Test
    void aPoisonRecordDoesNotBlockTheValidRecordBehindIt() {
        ProductEvents.Product phone = product().name("Kafka Behind Poison");

        publish(phone.productId, "{this is not json");
        publish(phone.productId, upserted(UUID.randomUUID(), phone));

        awaitName(phone.productId, "Kafka Behind Poison");
        assertEquals("{this is not json", awaitDeadLetter().value());
    }

    // ---------- I, J ----------

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
    void aTransientFailureIsRetriedAndThenAppliedExactlyOnce() {
        ProductEvents.Product phone = product().name("Kafka Flaky");
        AtomicInteger attempts = failTransientlyFor(phone.productId, TOTAL_ATTEMPTS - 1);

        publish(phone.productId, upserted(UUID.randomUUID(), phone));

        awaitName(phone.productId, "Kafka Flaky");
        assertEquals(TOTAL_ATTEMPTS, attempts.get());
        assertEquals(1, markers());
        assertNoDeadLetterWithin(Duration.ofSeconds(3));
    }

    @Test
    void aFailureThatStaysTransientIsDeadLetteredAfterExactlyTheRetryBudget() {
        ProductEvents.Product phone = product().name("Kafka Broken");
        String record = upserted(UUID.randomUUID(), phone);
        AtomicInteger attempts = failTransientlyFor(phone.productId, Integer.MAX_VALUE);

        publish(phone.productId, record);

        ConsumerRecord<String, String> dead = awaitDeadLetter();
        assertEquals(record, dead.value());
        assertEquals(phone.productId.toString(), dead.key());
        assertEquals(TOTAL_ATTEMPTS, attempts.get(), "initial delivery plus exactly two retries");
        assertEquals(0, markers());
        assertEquals(0, rows());

        sleep(2 * KafkaConsumerConfig.RETRY_INTERVAL_MS);
        assertEquals(TOTAL_ATTEMPTS, attempts.get(), "not attempted again after dead-lettering");
    }

    // ---------- tracing ----------

    @Test
    void theConsumerContinuesTheProducersTrace(CapturedOutput output) {
        ProductEvents.Product phone = product().name("Kafka Traced");
        UUID eventId = UUID.randomUUID();
        String traceId = randomHex(16);
        String producerSpanId = randomHex(8);

        publish(phone.productId, upserted(eventId, phone), traceparent(traceId, producerSpanId));

        awaitName(phone.productId, "Kafka Traced");
        String applied = await(() -> output.getOut().lines()
                .filter(line -> line.contains("Product event applied to recommendations eventId=" + eventId))
                .findFirst().orElse(null), "the projection log line");
        assertTrue(applied.contains("traceId=" + traceId), applied);
        assertFalse(applied.contains("spanId=" + producerSpanId), "the consumer opens its own span: " + applied);
    }
}
