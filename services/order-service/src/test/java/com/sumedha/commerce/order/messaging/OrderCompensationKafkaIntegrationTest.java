package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.order.dto.request.CreateOrderItemRequest;
import com.sumedha.commerce.order.dto.request.CreateOrderRequest;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.entity.OrderOutboxEvent;
import com.sumedha.commerce.order.enums.OrderStatus;
import com.sumedha.commerce.order.enums.OutboxEventStatus;
import com.sumedha.commerce.order.repository.OrderOutboxEventRepository;
import com.sumedha.commerce.order.repository.OrderRepository;
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
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The producing half of the saga through a real (embedded, KRaft) broker and real PostgreSQL:
 * a payment failure arrives on {@code payment.events.v1}, the order is cancelled, and the
 * compensation the same transaction promised actually reaches {@code order.compensation.v1}.
 *
 * <p>The consuming half lives in inventory-service and is covered by its own Kafka test; the two
 * meet only in the real-broker run, because they are separately deployed applications.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        topics = {KafkaTopics.PAYMENT_EVENTS_V1, KafkaTopics.PAYMENT_EVENTS_V1_DLT,
                KafkaTopics.ORDER_COMPENSATION_V1},
        partitions = 3)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces",
        // Poll aggressively so the asynchronous half of the flow does not dominate the runtime.
        "order.outbox.poll-interval=200ms"
})
class OrderCompensationKafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
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
    @Autowired private OrderOutboxEventRepository outboxEvents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;

    private Producer<String, String> producer;
    private Consumer<String, String> compensationProbe;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(producerConfig, new StringSerializer(), new StringSerializer());

        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "compensation-probe-" + UUID.randomUUID());
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        compensationProbe = new DefaultKafkaConsumerFactory<>(
                consumerConfig, new StringDeserializer(), new StringDeserializer()).createConsumer();

        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : compensationProbe.partitionsFor(KafkaTopics.ORDER_COMPENSATION_V1)) {
            partitions.add(new TopicPartition(info.topic(), info.partition()));
        }
        compensationProbe.assign(partitions);
        // Start at the end: the topic is shared by every test in this class, and each test must
        // only observe the records it produced itself.
        compensationProbe.seekToEnd(partitions);
        compensationProbe.poll(Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        producer.close(Duration.ofSeconds(5));
        compensationProbe.close();
        jdbc.update("delete from order_outbox_event");
        jdbc.update("delete from dead_letter_event");
        jdbc.update("delete from processed_event");
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
    }

    // ---------- helpers ----------

    private UUID createPendingOrder(UUID productId) {
        return orderService.create(new CreateOrderRequest(userId, "USD", List.of(
                new CreateOrderItemRequest(productId, "Widget", "sku-1",
                        new BigDecimal("19.99"), 3)))).id();
    }

    private void publishPaymentFailed(UUID orderId) {
        String value = JSON.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(),
                EventTypes.PAYMENT_FAILED, 1, Instant.now(),
                new PaymentFailedEvent(UUID.randomUUID(), orderId, userId, "card declined")));
        producer.send(new ProducerRecord<>(KafkaTopics.PAYMENT_EVENTS_V1, orderId.toString(), value));
        producer.flush();
    }

    private void publishPaymentAuthorized(UUID orderId) {
        String value = JSON.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(),
                EventTypes.PAYMENT_AUTHORIZED, 1, Instant.now(),
                new PaymentAuthorizedEvent(UUID.randomUUID(), orderId, userId,
                        new BigDecimal("59.97"), "USD")));
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

    /** Everything published for one order during {@code quietFor}, ignoring any other order. */
    private List<ConsumerRecord<String, String>> drainCompensationTopic(UUID orderId, Duration quietFor) {
        List<ConsumerRecord<String, String>> drained = new ArrayList<>();
        long until = System.nanoTime() + quietFor.toNanos();
        while (System.nanoTime() < until) {
            ConsumerRecords<String, String> batch = compensationProbe.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : batch) {
                if (orderId.toString().equals(record.key())) {
                    drained.add(record);
                }
            }
        }
        return drained;
    }

    private ConsumerRecord<String, String> awaitCompensationRecord(UUID orderId) {
        return await(() -> {
            ConsumerRecords<String, String> batch = compensationProbe.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : batch) {
                if (orderId.toString().equals(record.key())) {
                    return record;
                }
            }
            return null;
        }, "a record for order " + orderId + " on " + KafkaTopics.ORDER_COMPENSATION_V1);
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---------- A + B: failure cancels the order and the compensation actually reaches Kafka ----------

    @Test
    void aPaymentFailureCancelsTheOrderAndPublishesItsCompensationToTheCompensationTopic() {
        UUID productId = UUID.randomUUID();
        UUID orderId = createPendingOrder(productId);

        publishPaymentFailed(orderId);
        awaitStatus(orderId, OrderStatus.CANCELLED);

        OrderOutboxEvent queued = await(() -> outboxEvents.findAll().stream()
                .filter(row -> row.getAggregateId().equals(orderId))
                .findFirst().orElse(null), "the compensation outbox row");
        assertEquals(EventTypes.INVENTORY_RELEASE_REQUESTED, queued.getEventType());

        ConsumerRecord<String, String> published = awaitCompensationRecord(orderId);
        assertEquals(KafkaTopics.ORDER_COMPENSATION_V1, published.topic());
        assertEquals(orderId.toString(), published.key(),
                "keyed by orderId so one order's compensation stays ordered on one partition");

        JsonNode envelope = JSON.readTree(published.value());
        assertEquals(queued.getEventId().toString(), envelope.get("eventId").stringValue(),
                "the published eventId is the one the business transaction committed");
        assertEquals("InventoryReleaseRequested", envelope.get("eventType").stringValue());
        assertEquals(1, envelope.get("schemaVersion").asInt());
        assertEquals(orderId.toString(), envelope.get("payload").get("orderId").stringValue());
        assertEquals(productId.toString(),
                envelope.get("payload").get("lines").get(0).get("productId").stringValue());
        assertEquals(3, envelope.get("payload").get("lines").get(0).get("quantity").asInt());
        assertTrue(envelope.get("payload").get("reason").stringValue().contains("card declined"));

        // The row is only PUBLISHED once the broker acknowledged it.
        await(() -> outboxEvents.findById(queued.getId())
                        .filter(row -> row.getStatus() == OutboxEventStatus.PUBLISHED).orElse(null),
                "the outbox row to be marked PUBLISHED");
        assertNotNull(outboxEvents.findById(queued.getId()).orElseThrow().getPublishedAt());
    }

    @Test
    void thePublishedPayloadIsExactlyTheBytesStoredInTheOutbox() {
        UUID orderId = createPendingOrder(UUID.randomUUID());

        publishPaymentFailed(orderId);
        awaitStatus(orderId, OrderStatus.CANCELLED);
        OrderOutboxEvent queued = await(() -> outboxEvents.findAll().stream()
                .filter(row -> row.getAggregateId().equals(orderId))
                .findFirst().orElse(null), "the compensation outbox row");

        ConsumerRecord<String, String> published = awaitCompensationRecord(orderId);

        assertEquals(queued.getPayload(), published.value(),
                "the publisher republishes the promised bytes; it never re-renders the event");
    }

    // ---------- the authorized path must stay silent ----------

    @Test
    void aSuccessfulPaymentConfirmsTheOrderAndPublishesNoCompensationAtAll() {
        UUID orderId = createPendingOrder(UUID.randomUUID());

        publishPaymentAuthorized(orderId);
        awaitStatus(orderId, OrderStatus.CONFIRMED);

        // Give the outbox poller several cycles to prove it has nothing to send.
        List<ConsumerRecord<String, String>> onCompensationTopic = drainCompensationTopic(orderId, Duration.ofSeconds(3));

        assertEquals(0, onCompensationTopic.size(),
                () -> "a confirmed order must not release its stock; found " + onCompensationTopic.size()
                        + " compensation record(s)");
        assertEquals(0, outboxEvents.findByAggregateIdOrderByCreatedAtAscIdAsc(orderId).size());
        assertEquals(OrderStatus.CONFIRMED, orders.findById(orderId).orElseThrow().getStatus());
    }

    // ---------- duplicate payment failure ----------

    @Test
    void aRedeliveredPaymentFailureProducesOnlyOneCompensationRecord() {
        UUID orderId = createPendingOrder(UUID.randomUUID());
        String value = JSON.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(),
                EventTypes.PAYMENT_FAILED, 1, Instant.now(),
                new PaymentFailedEvent(UUID.randomUUID(), orderId, userId, "card declined")));

        // The identical record three times, as an at-least-once redelivery would.
        for (int i = 0; i < 3; i++) {
            producer.send(new ProducerRecord<>(KafkaTopics.PAYMENT_EVENTS_V1, orderId.toString(), value));
        }
        producer.flush();
        awaitStatus(orderId, OrderStatus.CANCELLED);

        List<ConsumerRecord<String, String>> onCompensationTopic = drainCompensationTopic(orderId, Duration.ofSeconds(4));

        assertEquals(1, onCompensationTopic.size(),
                () -> "one payment failure owes one release; found " + onCompensationTopic.size());
        assertEquals(1, outboxEvents.findByAggregateIdOrderByCreatedAtAscIdAsc(orderId).size());
    }
}
