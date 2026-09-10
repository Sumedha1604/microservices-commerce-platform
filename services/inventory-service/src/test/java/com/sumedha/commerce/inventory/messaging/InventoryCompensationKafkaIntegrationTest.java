package com.sumedha.commerce.inventory.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.inventory.InventoryReleaseLine;
import com.sumedha.commerce.common.events.inventory.InventoryReleaseRequestedEvent;
import com.sumedha.commerce.inventory.dto.request.CreateInventoryRequest;
import com.sumedha.commerce.inventory.dto.request.StockQuantityRequest;
import com.sumedha.commerce.inventory.entity.Inventory;
import com.sumedha.commerce.inventory.repository.InventoryRepository;
import com.sumedha.commerce.inventory.repository.ProcessedEventRepository;
import com.sumedha.commerce.inventory.service.InventoryService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * The consuming half of the saga through a real (embedded, KRaft) broker and real PostgreSQL:
 * a compensation event published to {@code order.compensation.v1} actually gives the stock back,
 * exactly once, and a record the consumer cannot act on lands on the dead-letter topic instead of
 * spinning forever.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        topics = {KafkaTopics.ORDER_COMPENSATION_V1, KafkaTopics.ORDER_COMPENSATION_V1_DLT},
        partitions = 3)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
class InventoryCompensationKafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inventory_test")
            .withUsername("inventory")
            .withPassword("inventory");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private InventoryService inventoryService;
    @Autowired private InventoryRepository inventories;
    @Autowired private ProcessedEventRepository processedEvents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;

    /** Spied so a transient failure can be injected for one specific event; real otherwise. */
    @MockitoSpyBean private InventoryReleaseProcessor processor;

    private Producer<String, String> producer;
    private Consumer<String, String> deadLetterProbe;
    private final UUID orderId = UUID.randomUUID();

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
        for (PartitionInfo info : deadLetterProbe.partitionsFor(KafkaTopics.ORDER_COMPENSATION_V1_DLT)) {
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
        jdbc.update("delete from processed_event");
        jdbc.update("delete from inventory");
    }

    // ---------- helpers ----------

    private UUID stockWithReservation(int quantity, int reserved) {
        UUID productId = UUID.randomUUID();
        var created = inventoryService.create(new CreateInventoryRequest(productId, quantity));
        if (reserved > 0) {
            inventoryService.reserve(created.id(), new StockQuantityRequest(reserved));
        }
        return productId;
    }

    private String releaseEvent(UUID eventId, UUID productId, int quantity) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId,
                EventTypes.INVENTORY_RELEASE_REQUESTED, EventEnvelope.SCHEMA_VERSION_V1, Instant.now(),
                new InventoryReleaseRequestedEvent(orderId, "Payment failed: card declined",
                        List.of(new InventoryReleaseLine(productId, quantity)))));
    }

    private void publish(String value) {
        producer.send(new ProducerRecord<>(KafkaTopics.ORDER_COMPENSATION_V1, orderId.toString(), value));
        producer.flush();
    }

    private <T> T await(Supplier<T> attempt, String what) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            T value = attempt.get();
            if (value != null) {
                return value;
            }
            sleep();
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private Inventory stockOf(UUID productId) {
        return inventories.findByProductId(productId).orElseThrow();
    }

    private void awaitReserved(UUID productId, int expected) {
        await(() -> stockOf(productId).getReservedQuantity() == expected ? Boolean.TRUE : null,
                "product " + productId + " reserved quantity to reach " + expected);
    }

    private ConsumerRecord<String, String> awaitDeadLetter() {
        return await(() -> {
            ConsumerRecords<String, String> batch = deadLetterProbe.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : batch) {
                return record;
            }
            return null;
        }, "a record on " + KafkaTopics.ORDER_COMPENSATION_V1_DLT);
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---------- C: the event actually restores stock ----------

    @Test
    void aCompensationEventFromTheTopicReleasesTheReservation() {
        UUID eventId = UUID.randomUUID();
        UUID productId = stockWithReservation(10, 3);

        publish(releaseEvent(eventId, productId, 3));

        awaitReserved(productId, 0);
        assertEquals(10, stockOf(productId).getQuantity(), "owned stock is untouched by compensation");
        assertEquals(10, stockOf(productId).getAvailableQuantity());
        assertTrue(processedEvents.existsById(eventId));
    }

    // ---------- D: duplicate delivery restores stock only once ----------

    @Test
    void redeliveringTheSameEventOverTheBrokerRestoresStockOnlyOnce() {
        UUID eventId = UUID.randomUUID();
        UUID productId = stockWithReservation(10, 4);
        String event = releaseEvent(eventId, productId, 4);

        // The identical bytes three times, exactly as an outbox republish would deliver them.
        publish(event);
        awaitReserved(productId, 0);
        publish(event);
        publish(event);

        // Let the redeliveries be consumed, then prove nothing moved.
        sleep();
        sleep();
        sleep();
        assertEquals(0, stockOf(productId).getReservedQuantity(),
                "reserved must not be driven negative by a redelivery");
        assertEquals(10, stockOf(productId).getQuantity(),
                "and owned stock must not be inflated either");
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_event", Integer.class));
    }

    // ---------- E: records the consumer cannot act on go to the dead-letter topic ----------

    @Test
    void aMalformedRecordIsDeadLetteredWithoutTouchingStock() {
        UUID productId = stockWithReservation(10, 3);

        publish("{this is not json");

        ConsumerRecord<String, String> deadLettered = awaitDeadLetter();
        assertEquals("{this is not json", deadLettered.value(),
                "the original bytes are preserved for inspection");
        assertEquals(orderId.toString(), deadLettered.key());
        assertEquals(3, stockOf(productId).getReservedQuantity(), "no stock moved");
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_event", Integer.class));
    }

    @Test
    void anUnknownProductIsDeadLetteredRatherThanRetriedForever() {
        String event = releaseEvent(UUID.randomUUID(), UUID.randomUUID(), 2);

        publish(event);

        ConsumerRecord<String, String> deadLettered = awaitDeadLetter();
        assertEquals(event, deadLettered.value());
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_event", Integer.class),
                "a record that was never applied leaves no marker");
    }

    @Test
    void anUnsupportedSchemaVersionIsDeadLettered() {
        UUID productId = stockWithReservation(10, 3);
        String futureSchema = releaseEvent(UUID.randomUUID(), productId, 3)
                .replace("\"schemaVersion\":1", "\"schemaVersion\":9");

        publish(futureSchema);

        assertEquals(futureSchema, awaitDeadLetter().value());
        assertEquals(3, stockOf(productId).getReservedQuantity(),
                "an event this consumer does not understand must never move stock");
    }

    // ---------- E: transient failures are retried, boundedly ----------

    @Test
    void aTransientFailureIsRetriedAndTheReleaseStillHappensExactlyOnce() {
        UUID eventId = UUID.randomUUID();
        UUID productId = stockWithReservation(10, 3);
        AtomicInteger attempts = failTransientlyFor(eventId, 2);

        publish(releaseEvent(eventId, productId, 3));

        awaitReserved(productId, 0);
        assertEquals(3, attempts.get(), "two transient failures, then success on the last bounded attempt");
        assertEquals(10, stockOf(productId).getQuantity());
        assertTrue(processedEvents.existsById(eventId));
        assertNoDeadLetterWithin(Duration.ofSeconds(3));
    }

    @Test
    void aFailureThatStaysTransientIsRetriedABoundedNumberOfTimesThenDeadLettered() {
        UUID eventId = UUID.randomUUID();
        UUID productId = stockWithReservation(10, 3);
        String event = releaseEvent(eventId, productId, 3);
        AtomicInteger attempts = failTransientlyFor(eventId, Integer.MAX_VALUE);

        publish(event);

        assertEquals(event, awaitDeadLetter().value());
        assertEquals(3, attempts.get(), "initial delivery plus exactly two retries - never an infinite loop");
        assertEquals(3, stockOf(productId).getReservedQuantity(), "a failed release moved no stock");
        assertFalse(processedEvents.existsById(eventId), "and left no marker to swallow a later replay");
    }

    /**
     * Makes the first {@code failures} attempts for one event fail the way a database blip would,
     * then lets the real processor run. Other events are untouched.
     */
    private AtomicInteger failTransientlyFor(UUID eventId, int failures) {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            InventoryReleaseCommand command = invocation.getArgument(0);
            if (command.eventId().equals(eventId) && attempts.incrementAndGet() <= failures) {
                throw new TransientDataAccessResourceException("simulated database blip");
            }
            return invocation.callRealMethod();
        }).when(processor).process(any());
        return attempts;
    }

    private void assertNoDeadLetterWithin(Duration window) {
        long until = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < until) {
            ConsumerRecords<String, String> batch = deadLetterProbe.poll(Duration.ofMillis(300));
            assertTrue(batch.isEmpty(), "nothing should have been dead-lettered");
        }
    }

    /**
     * A poison record must not block the compensation queued behind it - stock held behind a
     * stuck partition is the failure mode this whole milestone exists to avoid.
     */
    @Test
    void aPoisonRecordDoesNotStallTheCompensationBehindIt() {
        UUID productId = stockWithReservation(10, 5);

        publish("{this is not json");
        publish(releaseEvent(UUID.randomUUID(), productId, 5));

        awaitReserved(productId, 0);
        assertEquals(10, stockOf(productId).getQuantity());
    }
}
