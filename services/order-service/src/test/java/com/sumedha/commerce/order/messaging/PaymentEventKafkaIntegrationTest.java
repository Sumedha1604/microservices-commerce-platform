package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.order.dto.request.CreateOrderItemRequest;
import com.sumedha.commerce.order.dto.request.CreateOrderRequest;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.enums.OrderStatus;
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
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * End-to-end through a real (embedded, KRaft) broker into real PostgreSQL: a record published to
 * {@code payment.events.v1} drives the order transition and the {@code processed_event} marker,
 * and anything unprocessable lands on {@code payment.events.v1.DLT} with its key intact instead
 * of being retried forever.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        topics = {KafkaTopics.PAYMENT_EVENTS_V1, KafkaTopics.PAYMENT_EVENTS_V1_DLT},
        partitions = 3)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces",
        // This test does not declare order.compensation.v1, so the compensation publisher
        // is parked rather than left sending into a topic that does not exist here.
        "order.outbox.enabled=false"
})
class PaymentEventKafkaIntegrationTest {

    private static final Duration APPLIED_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DLT_TIMEOUT = Duration.ofSeconds(25);
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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;
    @Autowired private KafkaListenerEndpointRegistry registry;

    /** Used only by the bounded-retry test to inject a transient failure. */
    @MockitoSpyBean private PaymentEventProcessor processor;

    private Producer<String, String> producer;
    private Consumer<String, String> deadLetters;
    private final UUID userId = UUID.randomUUID();

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
        deadLetters = new DefaultKafkaConsumerFactory<>(
                consumerConfig, new StringDeserializer(), new StringDeserializer()).createConsumer();

        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : deadLetters.partitionsFor(KafkaTopics.PAYMENT_EVENTS_V1_DLT)) {
            partitions.add(new TopicPartition(info.topic(), info.partition()));
        }
        deadLetters.assign(partitions);
        deadLetters.seekToEnd(partitions);
        deadLetters.poll(Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        producer.close(Duration.ofSeconds(5));
        deadLetters.close();
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
        ProducerRecord<String, String> record =
                new ProducerRecord<>(KafkaTopics.PAYMENT_EVENTS_V1, orderId.toString(), value);
        // the producer service always stamps this; prove it survives consumption
        record.headers().add(new RecordHeader("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01".getBytes(StandardCharsets.UTF_8)));
        producer.send(record);
        producer.flush();
    }

    private OrderStatus awaitStatus(UUID orderId, OrderStatus expected) {
        long deadline = System.nanoTime() + APPLIED_TIMEOUT.toNanos();
        OrderStatus seen = null;
        while (System.nanoTime() < deadline) {
            seen = orders.findById(orderId).map(Order::getStatus).orElse(null);
            if (seen == expected) {
                return seen;
            }
            sleep();
        }
        throw new AssertionError("order " + orderId + " never reached " + expected + " (last seen " + seen + ")");
    }

    private ConsumerRecord<String, String> awaitDeadLetter() {
        long deadline = System.nanoTime() + DLT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> batch = deadLetters.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : batch) {
                return record;
            }
        }
        throw new AssertionError("no record reached " + KafkaTopics.PAYMENT_EVENTS_V1_DLT + " in time");
    }

    private void assertNoDeadLetter(Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> batch = deadLetters.poll(Duration.ofMillis(200));
            assertTrue(batch.isEmpty(), () -> "unexpected dead-lettered record: " + batch.iterator().next());
        }
    }

    /** There are two listener containers now; this is the business one, chosen by its topic. */
    private ConcurrentMessageListenerContainer<?, ?> businessListenerContainer() {
        return registry.getListenerContainers().stream()
                .map(c -> (ConcurrentMessageListenerContainer<?, ?>) c)
                .filter(c -> List.of(c.getContainerProperties().getTopics())
                        .contains(KafkaTopics.PAYMENT_EVENTS_V1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no listener container for "
                        + KafkaTopics.PAYMENT_EVENTS_V1));
    }

    private int markerCount() {
        return jdbc.queryForObject("select count(*) from processed_event", Integer.class);
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---------- happy paths through a real broker ----------

    @Test
    void aPaymentAuthorizedRecordConfirmsTheOrderAndWritesTheMarker() {
        UUID orderId = createPendingOrder();
        UUID eventId = UUID.randomUUID();

        publish(orderId, authorizedJson(eventId, orderId));

        assertEquals(OrderStatus.CONFIRMED, awaitStatus(orderId, OrderStatus.CONFIRMED));
        Map<String, Object> marker = jdbc.queryForMap("select * from processed_event where event_id = ?", eventId);
        assertEquals("PaymentAuthorized", marker.get("event_type"));
        assertEquals(orderId, marker.get("order_id"));
    }

    @Test
    void aPaymentFailedRecordCancelsTheOrder() {
        UUID orderId = createPendingOrder();
        UUID eventId = UUID.randomUUID();

        publish(orderId, failedJson(eventId, orderId));

        assertEquals(OrderStatus.CANCELLED, awaitStatus(orderId, OrderStatus.CANCELLED));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from processed_event where event_id = ?", Integer.class, eventId));
    }

    @Test
    void redeliveryOfTheSameRecordLeavesExactlyOneMarker() {
        UUID orderId = createPendingOrder();
        UUID eventId = UUID.randomUUID();
        String value = authorizedJson(eventId, orderId);

        publish(orderId, value);
        awaitStatus(orderId, OrderStatus.CONFIRMED);
        publish(orderId, value);

        // give the second delivery time to be consumed before asserting
        sleep();
        sleep();
        assertEquals(OrderStatus.CONFIRMED, orders.findById(orderId).orElseThrow().getStatus());
        assertEquals(1, markerCount());
        assertNoDeadLetter(Duration.ofSeconds(2));
    }

    // ---------- consumer wiring ----------

    @Test
    void theListenerRunsInTheOrderServiceGroupOnTheV1TopicWithRecordAcksAndObservation() {
        ConcurrentMessageListenerContainer<?, ?> container = businessListenerContainer();

        assertEquals("order-service", container.getGroupId());
        assertEquals(List.of(KafkaTopics.PAYMENT_EVENTS_V1),
                List.of(container.getContainerProperties().getTopics()));
        assertEquals(ContainerProperties.AckMode.RECORD, container.getContainerProperties().getAckMode());
        assertTrue(container.getContainerProperties().isObservationEnabled(),
                "consumer observation must be on so the producer's traceparent is continued");
        assertEquals(3, container.getConcurrency(), "concurrency must not exceed the partition count");
        assertEquals(3, deadLetters.partitionsFor(KafkaTopics.PAYMENT_EVENTS_V1).size());
        assertEquals(3, deadLetters.partitionsFor(KafkaTopics.PAYMENT_EVENTS_V1_DLT).size());
    }

    // ---------- dead-letter routing ----------

    @Test
    void authorizingACancelledOrderIsDeadLetteredWithTheOriginalKeyAndNoMarker() {
        UUID orderId = createPendingOrder();
        publish(orderId, failedJson(UUID.randomUUID(), orderId));
        awaitStatus(orderId, OrderStatus.CANCELLED);
        int markersBefore = markerCount();

        UUID poisonEventId = UUID.randomUUID();
        publish(orderId, authorizedJson(poisonEventId, orderId));

        ConsumerRecord<String, String> deadLettered = awaitDeadLetter();
        assertEquals(orderId.toString(), deadLettered.key(), "the original key must be preserved");
        assertTrue(deadLettered.value().contains(poisonEventId.toString()),
                "the original value must be preserved");
        assertNotNull(deadLettered.headers().lastHeader("traceparent"),
                "the producer's trace context must survive to the dead-letter topic");

        assertEquals(OrderStatus.CANCELLED, orders.findById(orderId).orElseThrow().getStatus());
        assertEquals(markersBefore, markerCount(), "a dead-lettered event must leave no marker");
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from processed_event where event_id = ?", Integer.class, poisonEventId));
    }

    @Test
    void malformedJsonIsDeadLettered() {
        UUID orderId = createPendingOrder();

        publish(orderId, "{this is not json");

        ConsumerRecord<String, String> deadLettered = awaitDeadLetter();
        assertEquals(orderId.toString(), deadLettered.key());
        assertEquals("{this is not json", deadLettered.value());
        assertEquals(0, markerCount());
        assertEquals(OrderStatus.PENDING, orders.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    void anUnknownEventTypeIsDeadLettered() {
        UUID orderId = createPendingOrder();

        publish(orderId, authorizedJson(UUID.randomUUID(), orderId)
                .replace("\"PaymentAuthorized\"", "\"PaymentRefunded\""));

        assertEquals(orderId.toString(), awaitDeadLetter().key());
        assertEquals(0, markerCount());
        assertEquals(OrderStatus.PENDING, orders.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    void anUnsupportedSchemaVersionIsDeadLettered() {
        UUID orderId = createPendingOrder();

        publish(orderId, authorizedJson(UUID.randomUUID(), orderId)
                .replace("\"schemaVersion\":1", "\"schemaVersion\":9"));

        assertEquals(orderId.toString(), awaitDeadLetter().key());
        assertEquals(0, markerCount());
        assertEquals(OrderStatus.PENDING, orders.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    void anUnknownOrderIsDeadLettered() {
        UUID unknownOrderId = UUID.randomUUID();

        publish(unknownOrderId, authorizedJson(UUID.randomUUID(), unknownOrderId));

        assertEquals(unknownOrderId.toString(), awaitDeadLetter().key());
        assertEquals(0, markerCount());
        assertNull(orders.findById(unknownOrderId).orElse(null));
    }

    // ---------- bounded retry ----------

    @Test
    void aTransientFailureIsRetriedABoundedNumberOfTimesThenDeadLettered() {
        UUID orderId = createPendingOrder();
        UUID eventId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("database temporarily unavailable"))
                .when(processor).process(any());

        publish(orderId, authorizedJson(eventId, orderId));

        ConsumerRecord<String, String> deadLettered = awaitDeadLetter();
        assertEquals(orderId.toString(), deadLettered.key());
        // initial delivery + 2 retries, then recovery - never unbounded
        verify(processor, timeout(5_000).times(3)).process(any());
        assertEquals(OrderStatus.PENDING, orders.findById(orderId).orElseThrow().getStatus());
        assertEquals(0, markerCount());
    }
}
