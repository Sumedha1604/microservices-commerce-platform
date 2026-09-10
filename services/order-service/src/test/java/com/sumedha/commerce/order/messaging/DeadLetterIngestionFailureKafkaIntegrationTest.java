package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.order.dto.request.CreateOrderItemRequest;
import com.sumedha.commerce.order.dto.request.CreateOrderRequest;
import com.sumedha.commerce.order.entity.DeadLetterEvent;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.enums.OrderStatus;
import com.sumedha.commerce.order.repository.DeadLetterEventRepository;
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
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What happens when the inspection write itself fails - the case the ADR calls out as a known
 * limitation, and the one where getting the topology wrong would be worst.
 *
 * <p>Two guarantees are asserted, and they pull in opposite directions:
 *
 * <ul>
 *   <li><b>A capture failure is never republished to the dead-letter topic.</b> The DLT listener
 *       runs on a container factory with no {@code DeadLetterPublishingRecoverer}, because
 *       re-dead-lettering a record that is <em>already</em> on the dead-letter topic would feed
 *       the topic from itself - one database outage would multiply into an unbounded loop.</li>
 *   <li><b>A capture failure does not stall the partition.</b> After bounded retries the record
 *       is logged at ERROR and skipped, so the records queued behind it are still captured.</li>
 * </ul>
 *
 * <p>The database failure is injected by mocking {@link DeadLetterRecorder} for one specific
 * event and delegating every other record to a real recorder, so both outcomes can be observed
 * on the same partition, in order, in a single run.
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
class DeadLetterIngestionFailureKafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(40);
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

    /** Replaced so a database outage can be injected for one chosen record. */
    @MockitoBean
    private DeadLetterRecorder recorder;

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orders;
    @Autowired private DeadLetterEventRepository deadLetterEvents;
    @Autowired private DeadLetterPayloadInspector inspector;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;

    private Producer<String, String> producer;
    private Consumer<String, String> dltProbe;
    private final UUID userId = UUID.randomUUID();

    /** The one event whose capture will fail; every other record is recorded for real. */
    private final UUID uncapturable = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        DeadLetterRecorder real = new DeadLetterRecorder(deadLetterEvents, inspector);
        when(recorder.record(any())).thenAnswer(invocation -> {
            ConsumerRecord<String, String> record = invocation.getArgument(0);
            if (record.value() != null && record.value().contains(uncapturable.toString())) {
                throw new TransientDataAccessResourceException("database unavailable");
            }
            return real.record(record);
        });

        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(producerConfig, new StringSerializer(), new StringSerializer());

        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-probe-" + UUID.randomUUID());
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        dltProbe = new DefaultKafkaConsumerFactory<>(
                consumerConfig, new StringDeserializer(), new StringDeserializer()).createConsumer();

        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : dltProbe.partitionsFor(KafkaTopics.PAYMENT_EVENTS_V1_DLT)) {
            partitions.add(new TopicPartition(info.topic(), info.partition()));
        }
        dltProbe.assign(partitions);
        dltProbe.seekToBeginning(partitions);
    }

    @AfterEach
    void tearDown() {
        producer.close(Duration.ofSeconds(5));
        dltProbe.close();
        jdbc.update("delete from dead_letter_event");
        jdbc.update("delete from processed_event");
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
    }

    // ---------- helpers ----------

    private UUID createCancelledOrder() {
        UUID orderId = orderService.create(new CreateOrderRequest(userId, "USD", List.of(
                new CreateOrderItemRequest(UUID.randomUUID(), "Widget", "sku-1",
                        new BigDecimal("19.99"), 3)))).id();
        publish(orderId, JSON.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(),
                EventTypes.PAYMENT_FAILED, 1, Instant.now(),
                new PaymentFailedEvent(UUID.randomUUID(), orderId, userId, "card declined"))));
        await(() -> orders.findById(orderId).map(Order::getStatus)
                .filter(OrderStatus.CANCELLED::equals).orElse(null), "order " + orderId + " to be cancelled");
        return orderId;
    }

    /** An authorization for a cancelled order: always rejected, so it always reaches the DLT. */
    private String rejectedAuthorization(UUID eventId, UUID orderId) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId, EventTypes.PAYMENT_AUTHORIZED, 1,
                Instant.now(), new PaymentAuthorizedEvent(UUID.randomUUID(), orderId, userId,
                new BigDecimal("59.97"), "USD")));
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

    /** Everything currently on the dead-letter topic, read from the beginning. */
    private List<ConsumerRecord<String, String>> drainDeadLetterTopic() {
        List<ConsumerRecord<String, String>> drained = new ArrayList<>();
        long quietUntil = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < quietUntil) {
            ConsumerRecords<String, String> batch = dltProbe.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : batch) {
                drained.add(record);
            }
        }
        return drained;
    }

    private static long occurrencesOf(List<ConsumerRecord<String, String>> records, UUID eventId) {
        return records.stream().filter(r -> r.value() != null && r.value().contains(eventId.toString())).count();
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---------- the test ----------

    @Test
    void aRecordThatCannotBeCapturedIsSkippedWithoutBeingRepublishedOrStallingThePartition() {
        UUID orderId = createCancelledOrder();
        UUID capturable = UUID.randomUUID();

        // Both are keyed by the order id, so they land on the same partition in this order:
        // the one that cannot be captured is queued directly in front of the one that can.
        publish(orderId, rejectedAuthorization(uncapturable, orderId));
        publish(orderId, rejectedAuthorization(capturable, orderId));

        // The partition is not stalled: the record behind the failing one is still captured,
        // which can only happen once the failing one has been retried, logged and skipped.
        DeadLetterEvent captured = await(() -> deadLetterEvents.findAll().stream()
                        .filter(d -> capturable.equals(d.getEventId()))
                        .findFirst().orElse(null),
                "the record queued behind an uncapturable one to be captured");
        assertEquals(orderId, captured.getOrderId());

        // The failed capture left no inspection row - the documented cost of the skip.
        assertEquals(0, deadLetterEvents.findAll().stream()
                        .filter(d -> uncapturable.equals(d.getEventId())).count(),
                "a record whose capture failed has no inspection row");

        // And the important part: the capture failure was never republished to the DLT.
        List<ConsumerRecord<String, String>> onTheDeadLetterTopic = drainDeadLetterTopic();
        assertEquals(1, occurrencesOf(onTheDeadLetterTopic, uncapturable),
                "the uncapturable record must appear on the DLT exactly once - re-dead-lettering a "
                        + "capture failure would feed the dead-letter topic from itself");
        assertEquals(1, occurrencesOf(onTheDeadLetterTopic, capturable));
        assertEquals(2, onTheDeadLetterTopic.size(),
                () -> "unexpected extra records on the dead-letter topic: " + onTheDeadLetterTopic.size());

        // Neither record moved the order or left a processed marker.
        assertEquals(OrderStatus.CANCELLED, orders.findById(orderId).orElseThrow().getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_event where event_id in (?, ?)",
                Integer.class, uncapturable, capturable));
    }
}
