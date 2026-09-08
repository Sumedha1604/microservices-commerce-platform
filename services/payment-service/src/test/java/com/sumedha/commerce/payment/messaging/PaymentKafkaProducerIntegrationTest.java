package com.sumedha.commerce.payment.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.payment.dto.request.AuthorizePaymentRequest;
import com.sumedha.commerce.payment.dto.request.CreatePaymentRequest;
import com.sumedha.commerce.payment.dto.request.FailPaymentRequest;
import com.sumedha.commerce.payment.dto.response.PaymentResponse;
import com.sumedha.commerce.payment.entity.Payment;
import com.sumedha.commerce.payment.enums.PaymentStatus;
import com.sumedha.commerce.payment.repository.PaymentRepository;
import com.sumedha.commerce.payment.service.PaymentService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * H + G + A/B/D/E: a real record is produced to {@code payment.events.v1} on an embedded
 * KRaft broker, consumed back, and shown to be the explicit {@code EventEnvelope} JSON keyed
 * by {@code orderId}; publication is proven to wait for the DB commit and to be skipped on
 * rollback.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(topics = KafkaTopics.PAYMENT_EVENTS_V1, partitions = 3)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "payment.outbox.enabled=false",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
class PaymentKafkaProducerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_test")
            .withUsername("payment_user")
            .withPassword("payment_user");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentOutboxBatchProcessor outboxProcessor;
    @Autowired private com.sumedha.commerce.payment.repository.PaymentOutboxEventRepository outboxEvents;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;
    @Autowired private PlatformTransactionManager transactionManager;

    private Consumer<String, String> consumer;

    @BeforeEach
    void subscribeAtEndOfTopic() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-producer-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();

        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : consumer.partitionsFor(KafkaTopics.PAYMENT_EVENTS_V1)) {
            partitions.add(new TopicPartition(info.topic(), info.partition()));
        }
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        consumer.poll(Duration.ofMillis(200));
    }

    @AfterEach
    void closeConsumer() {
        if (consumer != null) {
            consumer.close();
        }
        outboxEvents.deleteAll();
        payments.deleteAll();
    }

    // ---------------------------------------------------------------------

    @Test
    void authorizePublishesAConsumablePaymentAuthorizedRecordKeyedByOrderId() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = paymentService.create(
                new CreatePaymentRequest(orderId, userId, java.math.BigDecimal.valueOf(59.97), "USD")).id();

        paymentService.authorize(paymentId, new AuthorizePaymentRequest("stripe", "ref-1"));
        UUID persistedEventId = outboxEvents.findAll().getFirst().getEventId();
        outboxProcessor.publishNextBatch();

        ConsumerRecord<String, String> record = singleRecord();
        assertEquals(orderId.toString(), record.key());
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1, record.topic());
        assertNotNull(record.headers().lastHeader("traceparent"),
                "KafkaTemplate observation must inject a W3C traceparent header");

        EventEnvelope<PaymentAuthorizedEvent> envelope =
                JSON.readValue(record.value(), new TypeReference<EventEnvelope<PaymentAuthorizedEvent>>() {});
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, envelope.eventType());
        assertEquals(persistedEventId, envelope.eventId());
        assertEquals(1, envelope.schemaVersion());
        assertEquals(paymentId, envelope.payload().paymentId());
        assertEquals(orderId, envelope.payload().orderId());
        assertEquals(userId, envelope.payload().userId());
        assertEquals(0, java.math.BigDecimal.valueOf(59.97).compareTo(envelope.payload().amount()));
        assertEquals("USD", envelope.payload().currency());
        assertEquals(com.sumedha.commerce.payment.enums.OutboxEventStatus.PUBLISHED,
                outboxEvents.findAll().getFirst().getStatus());
    }

    @Test
    void failPublishesAConsumablePaymentFailedRecord() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = paymentService.create(
                new CreatePaymentRequest(orderId, UUID.randomUUID(), java.math.BigDecimal.TEN, "USD")).id();

        paymentService.fail(paymentId, new FailPaymentRequest("  card declined  "));
        outboxProcessor.publishNextBatch();

        ConsumerRecord<String, String> record = singleRecord();
        assertEquals(orderId.toString(), record.key());

        EventEnvelope<PaymentFailedEvent> envelope =
                JSON.readValue(record.value(), new TypeReference<EventEnvelope<PaymentFailedEvent>>() {});
        assertEquals(EventTypes.PAYMENT_FAILED, envelope.eventType());
        assertEquals(orderId, envelope.payload().orderId());
        assertEquals("card declined", envelope.payload().failureReason());
    }

    @Test
    void theTopicIsCreatedWithThreePartitions() {
        assertEquals(3, consumer.partitionsFor(KafkaTopics.PAYMENT_EVENTS_V1).size());
    }

    @Test
    void noValueTypeHeaderIsAttachedToTheRecord() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = paymentService.create(
                new CreatePaymentRequest(orderId, UUID.randomUUID(), java.math.BigDecimal.TEN, "USD")).id();
        paymentService.authorize(paymentId, new AuthorizePaymentRequest("stripe", "ref-1"));
        outboxProcessor.publishNextBatch();

        ConsumerRecord<String, String> record = singleRecord();
        assertNull(record.headers().lastHeader("__TypeId__"));
        assertNull(record.headers().lastHeader("__Key_TypeId__"));
        assertTrue(record.value().startsWith("{\"eventId\":"));
    }

    @Test
    void publicationHappensOnlyAfterTheTransactionCommits() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = paymentService.create(
                new CreatePaymentRequest(orderId, UUID.randomUUID(), java.math.BigDecimal.TEN, "USD")).id();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            paymentService.authorize(paymentId, new AuthorizePaymentRequest("stripe", "ref-1"));
            // still inside the payment transaction: only the uncommitted outbox row exists
            assertTrue(pollRecords(Duration.ofSeconds(1)).isEmpty(),
                    "no event may be published before the payment transaction commits");
        });

        outboxProcessor.publishNextBatch();
        // transaction committed and the publisher ran: the event now appears
        ConsumerRecord<String, String> record = singleRecord();
        assertEquals(orderId.toString(), record.key());
        assertEquals(PaymentStatus.AUTHORIZED, payments.findById(paymentId).orElseThrow().getStatus());
    }

    @Test
    void aRolledBackTransitionPublishesNothing() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = paymentService.create(
                new CreatePaymentRequest(orderId, UUID.randomUUID(), java.math.BigDecimal.TEN, "USD")).id();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            paymentService.authorize(paymentId, new AuthorizePaymentRequest("stripe", "ref-1"));
            status.setRollbackOnly();
        });

        assertTrue(pollRecords(Duration.ofSeconds(2)).isEmpty(),
                "a rolled-back transition must publish nothing");
        assertEquals(0, outboxProcessor.publishNextBatch());
        Payment reloaded = payments.findById(paymentId).orElseThrow();
        assertEquals(PaymentStatus.PENDING, reloaded.getStatus(), "rollback must leave the payment PENDING");
    }

    // ---------------------------------------------------------------------

    private ConsumerRecord<String, String> singleRecord() {
        List<ConsumerRecord<String, String>> records = pollRecords(Duration.ofSeconds(10));
        assertEquals(1, records.size(), () -> "expected exactly one record, got " + records);
        return records.get(0);
    }

    private List<ConsumerRecord<String, String>> pollRecords(Duration timeout) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(200));
            batch.forEach(collected::add);
        } while (collected.isEmpty() && System.nanoTime() < deadline);
        return collected;
    }
}
