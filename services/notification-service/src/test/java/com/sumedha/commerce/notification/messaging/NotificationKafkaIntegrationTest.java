package com.sumedha.commerce.notification.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.notification.config.KafkaConsumerConfig;
import com.sumedha.commerce.notification.entity.Notification;
import com.sumedha.commerce.notification.enums.NotificationType;
import com.sumedha.commerce.notification.repository.NotificationRepository;
import com.sumedha.commerce.notification.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The consumer through a real (embedded, KRaft) broker and real PostgreSQL: a payment event
 * published to {@code payment.events.v1} becomes exactly one notification, and a record the consumer
 * cannot handle lands on {@code payment.events.v1.notification.DLT} - intact - instead of stalling
 * the partition or looping forever.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        topics = {KafkaTopics.PAYMENT_EVENTS_V1, KafkaTopics.PAYMENT_EVENTS_V1_NOTIFICATION_DLT},
        partitions = 3)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
@ExtendWith(OutputCaptureExtension.class)
class NotificationKafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int TOTAL_ATTEMPTS = (int) KafkaConsumerConfig.MAX_RETRIES + 1;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notification_test")
            .withUsername("notification")
            .withPassword("notification");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private NotificationRepository notifications;
    @Autowired private ProcessedEventRepository processedEvents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;
    @Autowired private MeterRegistry meterRegistry;

    /** Spied so transient failures can be injected for one specific event; real otherwise. */
    @MockitoSpyBean private NotificationEventProcessor processor;
    /** Spied only to count how many times a poison record was attempted. */
    @MockitoSpyBean private PaymentEventParser parser;

    private Producer<String, String> producer;
    private Consumer<String, String> deadLetterProbe;
    private final UUID orderId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
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
        deadLetterProbe = new DefaultKafkaConsumerFactory<>(
                consumerConfig, new StringDeserializer(), new StringDeserializer()).createConsumer();

        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : deadLetterProbe.partitionsFor(KafkaTopics.PAYMENT_EVENTS_V1_NOTIFICATION_DLT)) {
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
        jdbc.update("delete from notification");
        jdbc.update("delete from processed_event");
    }

    // ---------- helpers ----------

    private String authorizedEvent(UUID eventId) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId, EventTypes.PAYMENT_AUTHORIZED,
                EventEnvelope.SCHEMA_VERSION_V1, Instant.now(),
                new PaymentAuthorizedEvent(paymentId, orderId, userId, new BigDecimal("59.97"), "USD")));
    }

    private String failedEvent(UUID eventId) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId, EventTypes.PAYMENT_FAILED,
                EventEnvelope.SCHEMA_VERSION_V1, Instant.now(),
                new PaymentFailedEvent(paymentId, orderId, userId, "card declined")));
    }

    /** Keyed by orderId, as payment-service publishes - so every record in a test shares a partition. */
    private void publish(String value, Header... headers) {
        producer.send(new ProducerRecord<>(KafkaTopics.PAYMENT_EVENTS_V1, null, orderId.toString(), value,
                List.of(headers)));
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

    private Notification awaitNotification(UUID eventId) {
        return await(() -> notifications.findAll().stream()
                .filter(n -> n.getEventId().equals(eventId)).findFirst().orElse(null),
                "a notification for event " + eventId);
    }

    private ConsumerRecord<String, String> awaitDeadLetter() {
        return await(() -> {
            for (ConsumerRecord<String, String> record : deadLetterProbe.poll(Duration.ofMillis(300))) {
                return record;
            }
            return null;
        }, "a record on " + KafkaTopics.PAYMENT_EVENTS_V1_NOTIFICATION_DLT);
    }

    private void assertNoDeadLetterWithin(Duration window) {
        long until = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < until) {
            assertTrue(deadLetterProbe.poll(Duration.ofMillis(300)).isEmpty(), "nothing should have been dead-lettered");
        }
    }

    private int notificationCount() {
        return jdbc.queryForObject("select count(*) from notification", Integer.class);
    }

    private int markerCount() {
        return jdbc.queryForObject("select count(*) from processed_event", Integer.class);
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

    // ---------- A + B: one record, one notification ----------

    @Test
    void aPaymentAuthorizedRecordCreatesOneNotification() {
        UUID eventId = UUID.randomUUID();
        double persistedBefore = meterRegistry.get("notification.persisted").tag("type", "PAYMENT_AUTHORIZED").counter().count();

        publish(authorizedEvent(eventId));

        Notification notification = awaitNotification(eventId);
        assertEquals(NotificationType.PAYMENT_AUTHORIZED, notification.getNotificationType());
        assertEquals(orderId, notification.getOrderId());
        assertEquals(1, notifications.countByEventId(eventId));
        assertTrue(processedEvents.existsById(eventId));
        await(() -> meterRegistry.get("notification.persisted").tag("type", "PAYMENT_AUTHORIZED").counter().count()
                        == persistedBefore + 1 ? Boolean.TRUE : null,
                "notification_persisted_total{type=PAYMENT_AUTHORIZED} to advance by one");
    }

    @Test
    void aPaymentFailedRecordCreatesOneNotification() {
        UUID eventId = UUID.randomUUID();

        publish(failedEvent(eventId));

        Notification notification = awaitNotification(eventId);
        assertEquals(NotificationType.PAYMENT_FAILED, notification.getNotificationType());
        assertTrue(notification.getMessage().endsWith("Reason: card declined"));
        assertEquals(1, notifications.countByEventId(eventId));
    }

    // ---------- C: duplicates over the broker ----------

    /**
     * The identical bytes three times, exactly as an outbox republish would deliver them. A sentinel
     * on the same key follows them on the same partition, so once it is processed the duplicates
     * provably have been too - no sleeping and hoping.
     */
    @Test
    void theSameEventDeliveredThreeTimesCreatesExactlyOneNotification() {
        UUID eventId = UUID.randomUUID();
        String record = authorizedEvent(eventId);
        double duplicatesBefore = meterRegistry.get("notification.duplicate.ignored").counter().count();

        publish(record);
        publish(record);
        publish(record);
        UUID sentinel = UUID.randomUUID();
        publish(failedEvent(sentinel));

        awaitNotification(sentinel);
        assertEquals(1, notifications.countByEventId(eventId));
        assertEquals(2, notificationCount(), "one for the triplicated event, one for the sentinel");
        assertEquals(2, markerCount());
        assertEquals(duplicatesBefore + 2, meterRegistry.get("notification.duplicate.ignored").counter().count());
        assertNoDeadLetterWithin(Duration.ofSeconds(1));
    }

    // ---------- D + E: unreadable records go to the notification DLT at once ----------

    @Test
    void aMalformedRecordIsDeadLetteredImmediatelyWithKeyValueAndTraceHeaderIntact() {
        String traceId = randomHex(16);
        String spanId = randomHex(8);
        String poison = "{this is not json";

        publish(poison, traceparent(traceId, spanId));

        ConsumerRecord<String, String> deadLettered = awaitDeadLetter();
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1_NOTIFICATION_DLT, deadLettered.topic());
        assertEquals(poison, deadLettered.value(), "the original bytes are preserved for inspection");
        assertEquals(orderId.toString(), deadLettered.key(), "and so is the original key");
        assertEquals("00-" + traceId + "-" + spanId + "-01", header(deadLettered, "traceparent"),
                "the dead-letter publish must not stamp fresh trace context over the original");
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1, header(deadLettered, "kafka_dlt-original-topic"));
        assertEquals("notification-service", header(deadLettered, "kafka_dlt-original-consumer-group"));
        String exceptionHeaders = header(deadLettered, "kafka_dlt-exception-fqcn") + " "
                + header(deadLettered, "kafka_dlt-exception-cause-fqcn");
        assertTrue(exceptionHeaders.contains(NonRetryableEventException.class.getName()), exceptionHeaders);

        verify(parser, times(1)).parse(poison);
        assertEquals(0, notificationCount());
        assertEquals(0, markerCount());
    }

    @Test
    void anUnsupportedSchemaVersionIsDeadLetteredWithoutRetry() {
        String futureSchema = authorizedEvent(UUID.randomUUID()).replace("\"schemaVersion\":1", "\"schemaVersion\":2");

        publish(futureSchema);

        ConsumerRecord<String, String> deadLettered = awaitDeadLetter();
        assertEquals(futureSchema, deadLettered.value());
        assertEquals(orderId.toString(), deadLettered.key());
        verify(parser, times(1)).parse(futureSchema);
        assertEquals(0, notificationCount());
        assertEquals(0, markerCount());
    }

    @Test
    void anUnknownEventTypeAndAMissingOrderIdAreDeadLettered() {
        String unknownType = authorizedEvent(UUID.randomUUID()).replace("\"PaymentAuthorized\"", "\"PaymentCaptured\"");
        String missingOrder = failedEvent(UUID.randomUUID()).replace("\"orderId\":\"" + orderId + "\"", "\"orderId\":null");

        publish(unknownType);
        publish(missingOrder);

        assertEquals(unknownType, awaitDeadLetter().value());
        assertEquals(missingOrder, awaitDeadLetter().value());
        assertEquals(0, notificationCount());
        assertEquals(0, markerCount());
    }

    // ---------- F: a poison record does not stall the partition ----------

    @Test
    void aPoisonRecordDoesNotBlockTheGoodRecordBehindIt() {
        UUID good = UUID.randomUUID();

        publish("{this is not json");
        publish(authorizedEvent(good));

        awaitNotification(good);
        assertEquals(1, notificationCount());
        assertEquals("{this is not json", awaitDeadLetter().value());
    }

    // ---------- G + H: transient failures are retried, boundedly ----------

    /**
     * Makes the first {@code failures} attempts for one event fail the way a database blip would,
     * then lets the real processor run. Other events are untouched.
     */
    private AtomicInteger failTransientlyFor(UUID eventId, int failures) {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            PaymentEvent event = invocation.getArgument(0);
            if (event.eventId().equals(eventId) && attempts.incrementAndGet() <= failures) {
                throw new TransientDataAccessResourceException("simulated database blip");
            }
            return invocation.callRealMethod();
        }).when(processor).process(any());
        return attempts;
    }

    @Test
    void aTransientFailureIsRetriedAndThenSucceedsExactlyOnce() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger attempts = failTransientlyFor(eventId, TOTAL_ATTEMPTS - 1);

        publish(authorizedEvent(eventId));

        awaitNotification(eventId);
        assertEquals(TOTAL_ATTEMPTS, attempts.get(), "two transient failures, then success on the last bounded attempt");
        assertEquals(1, notifications.countByEventId(eventId));
        assertTrue(processedEvents.existsById(eventId));
        assertNoDeadLetterWithin(Duration.ofSeconds(3));
    }

    @Test
    void aFailureThatStaysTransientIsDeadLetteredAfterExactlyTheRetryBudget() {
        UUID eventId = UUID.randomUUID();
        String record = failedEvent(eventId);
        AtomicInteger attempts = failTransientlyFor(eventId, Integer.MAX_VALUE);

        publish(record);

        ConsumerRecord<String, String> deadLettered = awaitDeadLetter();
        assertEquals(record, deadLettered.value());
        assertEquals(orderId.toString(), deadLettered.key());
        assertEquals(TOTAL_ATTEMPTS, attempts.get(), "initial delivery plus exactly two retries - never an infinite loop");
        assertEquals(0, notifications.countByEventId(eventId), "nothing was written");
        assertFalse(processedEvents.existsById(eventId), "and no marker is left to swallow a later replay");

        sleep(2 * KafkaConsumerConfig.RETRY_INTERVAL_MS);
        assertEquals(TOTAL_ATTEMPTS, attempts.get(), "the record is not attempted again after dead-lettering");
    }

    // ---------- tracing ----------

    /**
     * The listener continues the producer's W3C trace: the notification is logged under the
     * producer's traceId, in a new (consumer) span rather than the producer's own span.
     */
    @Test
    void theConsumerContinuesTheProducersTrace(CapturedOutput output) {
        UUID eventId = UUID.randomUUID();
        String traceId = randomHex(16);
        String producerSpanId = randomHex(8);

        publish(authorizedEvent(eventId), traceparent(traceId, producerSpanId));

        Notification notification = awaitNotification(eventId);
        String createdLine = await(() -> output.getOut().lines()
                .filter(line -> line.contains("Notification created notificationId=" + notification.getId()))
                .findFirst().orElse(null), "the creation log line");
        assertNotNull(createdLine);
        assertTrue(createdLine.contains("traceId=" + traceId), createdLine);
        assertFalse(createdLine.contains("spanId=" + producerSpanId), "the consumer opens its own span: " + createdLine);
        assertTrue(createdLine.contains("eventId=" + eventId), createdLine);
        assertTrue(createdLine.contains("orderId=" + orderId), createdLine);
        verify(parser, times(1)).parse(anyString());
    }
}
