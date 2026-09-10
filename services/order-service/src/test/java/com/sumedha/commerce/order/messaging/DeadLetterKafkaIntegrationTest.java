package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.order.dto.request.CreateOrderItemRequest;
import com.sumedha.commerce.order.dto.request.CreateOrderRequest;
import com.sumedha.commerce.order.dto.response.DeadLetterReplayResponse;
import com.sumedha.commerce.order.entity.DeadLetterEvent;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.enums.DeadLetterStatus;
import com.sumedha.commerce.order.enums.OrderStatus;
import com.sumedha.commerce.order.repository.DeadLetterEventRepository;
import com.sumedha.commerce.order.repository.OrderRepository;
import com.sumedha.commerce.order.service.DeadLetterAdminService;
import com.sumedha.commerce.order.service.OrderService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole dead-letter loop through a real (embedded, KRaft) broker and real PostgreSQL:
 * an unprocessable record is dead-lettered by the business consumer, captured as an inspection
 * row by the DLT listener, and replayed back onto the original topic byte-for-byte.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        topics = {KafkaTopics.PAYMENT_EVENTS_V1, KafkaTopics.PAYMENT_EVENTS_V1_DLT},
        partitions = 3)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
class DeadLetterKafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(25);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_test")
            .withUsername("order_user")
            .withPassword("order_user");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orders;
    @Autowired private DeadLetterEventRepository deadLetterEvents;
    @Autowired private DeadLetterAdminService admin;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;
    @Autowired private KafkaListenerEndpointRegistry registry;

    private Producer<String, String> producer;
    private Consumer<String, String> paymentEventProbe;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(producerConfig, new StringSerializer(), new StringSerializer());

        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "replay-probe-" + UUID.randomUUID());
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        paymentEventProbe = new DefaultKafkaConsumerFactory<>(
                consumerConfig, new StringDeserializer(), new StringDeserializer()).createConsumer();

        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : paymentEventProbe.partitionsFor(KafkaTopics.PAYMENT_EVENTS_V1)) {
            partitions.add(new TopicPartition(info.topic(), info.partition()));
        }
        paymentEventProbe.assign(partitions);
        paymentEventProbe.seekToEnd(partitions);
        paymentEventProbe.poll(Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        producer.close(Duration.ofSeconds(5));
        paymentEventProbe.close();
        jdbc.update("delete from dead_letter_event");
        jdbc.update("delete from processed_event");
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
    }

    // ---------- helpers ----------

    private UUID createPendingOrder() {
        return orderService.create(new CreateOrderRequest(userId, "USD", List.of(
                new CreateOrderItemRequest(UUID.randomUUID(), "Widget", "sku-1", new BigDecimal("19.99"), 3)))).id();
    }

    private String authorizedJson(UUID eventId, UUID orderId) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId, EventTypes.PAYMENT_AUTHORIZED, 1, Instant.now(),
                new PaymentAuthorizedEvent(UUID.randomUUID(), orderId, userId, new BigDecimal("59.97"), "USD")));
    }

    private String failedJson(UUID eventId, UUID orderId) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId, EventTypes.PAYMENT_FAILED, 1, Instant.now(),
                new PaymentFailedEvent(UUID.randomUUID(), orderId, userId, "card declined")));
    }

    private void publish(UUID orderId, String value) {
        producer.send(new ProducerRecord<>(KafkaTopics.PAYMENT_EVENTS_V1, orderId.toString(), value));
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

    private void awaitStatus(UUID orderId, OrderStatus expected) {
        await(() -> orders.findById(orderId).map(Order::getStatus).filter(expected::equals).orElse(null),
                "order " + orderId + " to reach " + expected);
    }

    private DeadLetterEvent awaitCapturedRecord(UUID eventId) {
        return await(() -> deadLetterEvents.findAll().stream()
                .filter(d -> eventId.equals(d.getEventId()))
                .findFirst().orElse(null), "DLT capture of event " + eventId);
    }

    private long capturedCount(UUID eventId) {
        return deadLetterEvents.findAll().stream().filter(d -> eventId.equals(d.getEventId())).count();
    }

    /** Drops anything already on the topic so the next record read is the replayed one. */
    private void resetProbeToEnd() {
        List<TopicPartition> partitions = new ArrayList<>(paymentEventProbe.assignment());
        paymentEventProbe.seekToEnd(partitions);
        paymentEventProbe.poll(Duration.ofMillis(200));
    }

    private ConsumerRecord<String, String> awaitReplayedRecord() {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> batch = paymentEventProbe.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : batch) {
                return record;
            }
        }
        throw new AssertionError("no record was republished to " + KafkaTopics.PAYMENT_EVENTS_V1);
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---------- wiring ----------

    @Test
    void theDltListenerRunsInItsOwnGroupSingleThreadedOnTheDeadLetterTopic() {
        ConcurrentMessageListenerContainer<?, ?> dlt = registry.getListenerContainers().stream()
                .map(c -> (ConcurrentMessageListenerContainer<?, ?>) c)
                .filter(c -> List.of(c.getContainerProperties().getTopics())
                        .contains(KafkaTopics.PAYMENT_EVENTS_V1_DLT))
                .findFirst().orElseThrow(() -> new AssertionError("no DLT listener container"));

        assertEquals("order-service-dlt", dlt.getGroupId(),
                "DLT ingestion must not share the business consumer group");
        assertEquals(1, dlt.getConcurrency());
        assertEquals(ContainerProperties.AckMode.RECORD, dlt.getContainerProperties().getAckMode());
    }

    // ---------- capture ----------

    @Test
    void aSemanticFailureIsDeadLetteredAndCapturedAsAnInspectableRecord() {
        UUID orderId = createPendingOrder();
        publish(orderId, failedJson(UUID.randomUUID(), orderId));
        awaitStatus(orderId, OrderStatus.CANCELLED);

        UUID poisonEventId = UUID.randomUUID();
        publish(orderId, authorizedJson(poisonEventId, orderId));

        DeadLetterEvent captured = awaitCapturedRecord(poisonEventId);
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, captured.getEventType());
        assertEquals(orderId, captured.getOrderId());
        assertEquals(1, captured.getSchemaVersion());
        assertEquals(orderId.toString(), captured.getEventKey());
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1, captured.getOriginalTopic());
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1_DLT, captured.getDltTopic());
        assertNotNull(captured.getOriginalPartition(), "the original partition header must be decoded");
        assertNotNull(captured.getOriginalOffset(), "the original offset header must be decoded");
        assertEquals("order-service", captured.getConsumerGroup());
        assertTrue(captured.getExceptionClass().endsWith("NonRetryableEventException"),
                () -> "unexpected exception class: " + captured.getExceptionClass());
        assertTrue(captured.getExceptionMessage().contains("CANCELLED"));
        assertEquals(DeadLetterStatus.NEW, captured.getStatus());
        assertEquals(0, captured.getReplayCount());

        assertEquals(OrderStatus.CANCELLED, orders.findById(orderId).orElseThrow().getStatus(),
                "capturing a dead letter must never move the order");
    }

    @Test
    void aMalformedRecordIsCapturedWithItsRawValueAndCannotBeReplayed() {
        UUID orderId = createPendingOrder();

        publish(orderId, "{this is not json");

        DeadLetterEvent captured = await(() -> deadLetterEvents.findAll().stream()
                .filter(d -> "{this is not json".equals(d.getPayload()))
                .findFirst().orElse(null), "capture of the malformed record");
        assertEquals(orderId.toString(), captured.getEventKey());
        assertTrue(captured.getEventId() == null && captured.getEventType() == null,
                "an unparseable payload yields no envelope metadata, and that is fine");

        assertThrows(BadRequestException.class, () -> admin.replay(captured.getId()));
        assertEquals(DeadLetterStatus.NEW, deadLetterEvents.findById(captured.getId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.PENDING, orders.findById(orderId).orElseThrow().getStatus());
    }

    // ---------- replay ----------

    @Test
    void replayRepublishesTheExactOriginalRecordAndTheStillInvalidEventReturnsToTheDlt() {
        UUID orderId = createPendingOrder();
        publish(orderId, failedJson(UUID.randomUUID(), orderId));
        awaitStatus(orderId, OrderStatus.CANCELLED);

        UUID poisonEventId = UUID.randomUUID();
        String originalPayload = authorizedJson(poisonEventId, orderId);
        publish(orderId, originalPayload);
        DeadLetterEvent captured = awaitCapturedRecord(poisonEventId);
        long dltOffsetBefore = captured.getDltOffset();

        resetProbeToEnd();
        DeadLetterReplayResponse replayed = admin.replay(captured.getId());

        // published back to the ORIGINAL topic, same key, byte-identical value, same eventId
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1, replayed.replayedToTopic());
        assertEquals(poisonEventId, replayed.eventId());
        ConsumerRecord<String, String> republished = awaitReplayedRecord();
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1, republished.topic());
        assertEquals(orderId.toString(), republished.key());
        assertEquals(originalPayload, republished.value(), "the payload must be republished byte-for-byte");
        assertTrue(republished.value().contains(poisonEventId.toString()));

        DeadLetterEvent afterReplay = deadLetterEvents.findById(captured.getId()).orElseThrow();
        assertEquals(DeadLetterStatus.REPLAYED, afterReplay.getStatus());
        assertNotNull(afterReplay.getReplayedAt());
        assertEquals(1, afterReplay.getReplayCount());

        // The order is still CANCELLED, so the replayed event is still semantically invalid: it
        // is dead-lettered a second time and captured as a second, distinct inspection row.
        await(() -> capturedCount(poisonEventId) == 2 ? Boolean.TRUE : null,
                "the replayed event to be dead-lettered again");
        DeadLetterEvent second = deadLetterEvents.findAll().stream()
                .filter(d -> poisonEventId.equals(d.getEventId()))
                .filter(d -> d.getDltOffset() != dltOffsetBefore)
                .findFirst().orElseThrow();
        assertEquals(poisonEventId, second.getEventId(), "the eventId is preserved across the round trip");
        assertEquals(originalPayload, second.getPayload());
        assertEquals(DeadLetterStatus.NEW, second.getStatus());
        assertNotEquals(captured.getId(), second.getId());

        assertEquals(OrderStatus.CANCELLED, orders.findById(orderId).orElseThrow().getStatus(),
                "replay must not have changed order state");
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_event where event_id = ?",
                Integer.class, poisonEventId), "a rejected event never gets a processed marker");
    }

    @Test
    void replayingAnEventWhoseIdWasAlreadyProcessedIsIgnoredByConsumerDeduplication() {
        UUID orderId = createPendingOrder();
        UUID eventId = UUID.randomUUID();
        String payload = authorizedJson(eventId, orderId);

        // Apply it once for real, so processed_event holds this eventId.
        publish(orderId, payload);
        awaitStatus(orderId, OrderStatus.CONFIRMED);
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_event where event_id = ?",
                Integer.class, eventId));

        // Now hand the very same envelope to the DLT capture path and replay it.
        UUID captureId = insertCapturedCopy(orderId, payload);
        resetProbeToEnd();
        DeadLetterReplayResponse replayed = admin.replay(captureId);
        assertEquals(eventId, replayed.eventId());

        ConsumerRecord<String, String> republished = awaitReplayedRecord();
        assertEquals(payload, republished.value());

        // Dedup absorbs it: still exactly one marker, order untouched, nothing dead-lettered.
        sleep();
        sleep();
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_event where event_id = ?",
                Integer.class, eventId), "replay must not bypass processed_event deduplication");
        assertEquals(OrderStatus.CONFIRMED, orders.findById(orderId).orElseThrow().getStatus());
    }

    /** Inserts an inspection row holding an existing payload, without going through the broker. */
    private UUID insertCapturedCopy(UUID orderId, String payload) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dead_letter_event (id, event_id, event_type, schema_version, order_id, "
                        + "original_topic, dlt_topic, dlt_partition, dlt_offset, dlt_timestamp, event_key, "
                        + "payload, first_seen_at, status, replay_count) "
                        + "values (?, ?, ?, 1, ?, ?, ?, 0, ?, now(), ?, ?, now(), 'NEW', 0)",
                id, UUID.fromString(JSON.readTree(payload).get("eventId").stringValue()),
                EventTypes.PAYMENT_AUTHORIZED, orderId, KafkaTopics.PAYMENT_EVENTS_V1,
                KafkaTopics.PAYMENT_EVENTS_V1_DLT, System.nanoTime() % 100_000, orderId.toString(), payload);
        return id;
    }
}
