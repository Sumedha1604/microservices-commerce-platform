package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.InternalServerException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.order.config.DeadLetterReplayProperties;
import com.sumedha.commerce.order.dto.response.DeadLetterEventDetailResponse;
import com.sumedha.commerce.order.dto.response.DeadLetterEventResponse;
import com.sumedha.commerce.order.dto.response.DeadLetterReplayResponse;
import com.sumedha.commerce.order.entity.DeadLetterEvent;
import com.sumedha.commerce.order.enums.DeadLetterStatus;
import com.sumedha.commerce.order.metrics.DeadLetterMetrics;
import com.sumedha.commerce.order.repository.DeadLetterEventRepository;
import com.sumedha.commerce.order.service.DeadLetterAdminService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dead-letter capture, inspection and replay against real PostgreSQL.
 *
 * <p>No Kafka here: the listeners are stopped, the bootstrap address is a dead port and the
 * producer is a mock, so this test exercises the persistence and replay-state rules without a
 * broker. Broker behaviour is covered by {@link DeadLetterKafkaIntegrationTest}.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:59997",
        // ...and the compensation outbox publisher stays parked: this test is not about it,
        // and a poller looking for work it will never find only adds noise.
        "order.outbox.enabled=false"
})
@Testcontainers
class DeadLetterPostgresIntegrationTest {

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

    /**
     * The replay producer. Mocked so no broker is needed and failures can be injected. Named
     * explicitly because order-service now declares two String templates - this one for
     * dead-letter republishing, and an observation-enabled one for compensation.
     */
    @MockitoBean(name = "deadLetterKafkaTemplate")
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired private DeadLetterRecorder recorder;
    @Autowired private DeadLetterEventListener listener;
    @Autowired private DeadLetterAdminService admin;
    @Autowired private DeadLetterEventRepository deadLetterEvents;
    @Autowired private PaymentEventParser parser;
    @Autowired private DeadLetterMetrics metrics;
    @Autowired private JdbcTemplate jdbc;

    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void clearDatabase() {
        reset(kafkaTemplate);
        jdbc.update("delete from dead_letter_event");
    }

    // ---------- helpers ----------

    private String authorizedJson(UUID eventId, UUID order) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId, EventTypes.PAYMENT_AUTHORIZED, 1,
                Instant.now(), new PaymentAuthorizedEvent(
                        UUID.randomUUID(), order, UUID.randomUUID(), new BigDecimal("59.97"), "USD")));
    }

    private ConsumerRecord<String, String> dltRecord(int partition, long offset, String key, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.PAYMENT_EVENTS_V1_DLT, partition, offset,
                System.currentTimeMillis(), TimestampType.CREATE_TIME,
                0, 0, key, value, new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());

        record.headers().add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC,
                KafkaTopics.PAYMENT_EVENTS_V1.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION,
                ByteBuffer.allocate(Integer.BYTES).putInt(2).array()));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET,
                ByteBuffer.allocate(Long.BYTES).putLong(41L).array()));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP,
                "order-service".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_FQCN,
                "com.sumedha.commerce.order.messaging.NonRetryableEventException".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                "order is CANCELLED".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01".getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private void stubAck(String topic, int partition, long offset) {
        @SuppressWarnings("unchecked")
        SendResult<String, String> result = mock(SendResult.class);
        when(result.getRecordMetadata()).thenReturn(
                new RecordMetadata(new TopicPartition(topic, partition), offset, 0, 0L, 0, 0));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(result));
    }

    private Map<String, Object> singleRow() {
        var rows = jdbc.queryForList("select * from dead_letter_event");
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    // ---------- capture ----------

    @Test
    void aDeadLetterRecordIsPersistedWithItsParsedEnvelopeAndKafkaCoordinates() {
        UUID eventId = UUID.randomUUID();
        String payload = authorizedJson(eventId, orderId);

        listener.onDeadLetterRecord(dltRecord(1, 100L, orderId.toString(), payload));

        Map<String, Object> row = singleRow();
        assertEquals(eventId, row.get("event_id"));
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, row.get("event_type"));
        assertEquals(1, row.get("schema_version"));
        assertEquals(orderId, row.get("order_id"));
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1, row.get("original_topic"));
        assertEquals(2, row.get("original_partition"));
        assertEquals(41L, row.get("original_offset"));
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1_DLT, row.get("dlt_topic"));
        assertEquals(1, row.get("dlt_partition"));
        assertEquals(100L, row.get("dlt_offset"));
        assertEquals(orderId.toString(), row.get("event_key"));
        assertEquals(payload, row.get("payload"));
        assertEquals("order-service", row.get("consumer_group"));
        assertTrue(((String) row.get("exception_class")).endsWith("NonRetryableEventException"));
        assertEquals("order is CANCELLED", row.get("exception_message"));
        assertNotNull(row.get("traceparent"));
        assertEquals("NEW", row.get("status"));
        assertEquals(0, ((Number) row.get("replay_count")).intValue());
        assertNull(row.get("replayed_at"));
    }

    @Test
    void theSameDltCoordinateIsNeverStoredTwice() {
        ConsumerRecord<String, String> record =
                dltRecord(1, 100L, orderId.toString(), authorizedJson(UUID.randomUUID(), orderId));

        listener.onDeadLetterRecord(record);
        listener.onDeadLetterRecord(record);
        listener.onDeadLetterRecord(record);

        assertEquals(1, deadLetterEvents.count(), "redelivery of a DLT record must not duplicate the row");
    }

    @Test
    void theUniqueConstraintItselfRejectsASecondRowForTheSameCoordinate() {
        ConsumerRecord<String, String> record =
                dltRecord(0, 7L, orderId.toString(), authorizedJson(UUID.randomUUID(), orderId));
        recorder.record(record);

        // Bypasses the recorder's exists-check to prove the database is the real guard.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into dead_letter_event (id, original_topic, dlt_topic, dlt_partition, dlt_offset, "
                        + "dlt_timestamp, payload, first_seen_at, status, replay_count) "
                        + "values (?, ?, ?, 0, 7, now(), '{}', now(), 'NEW', 0)",
                UUID.randomUUID(), KafkaTopics.PAYMENT_EVENTS_V1, KafkaTopics.PAYMENT_EVENTS_V1_DLT));
        assertEquals(1, deadLetterEvents.count());
    }

    @Test
    void aDifferentOffsetOnTheSamePartitionIsADistinctRecord() {
        listener.onDeadLetterRecord(dltRecord(1, 100L, orderId.toString(), authorizedJson(UUID.randomUUID(), orderId)));
        listener.onDeadLetterRecord(dltRecord(1, 101L, orderId.toString(), authorizedJson(UUID.randomUUID(), orderId)));

        assertEquals(2, deadLetterEvents.count());
    }

    // ---------- malformed payloads stay inspectable ----------

    @Test
    void malformedJsonIsStillStoredAsAnInspectableRecord() {
        listener.onDeadLetterRecord(dltRecord(0, 5L, orderId.toString(), "{this is not json"));

        Map<String, Object> row = singleRow();
        assertEquals("{this is not json", row.get("payload"), "the raw value must be preserved verbatim");
        assertNull(row.get("event_id"));
        assertNull(row.get("event_type"));
        assertNull(row.get("order_id"));
        // The Kafka-level facts are still there, which is what makes the row worth having.
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1, row.get("original_topic"));
        assertEquals(0, row.get("dlt_partition"));
        assertEquals(5L, row.get("dlt_offset"));
        assertEquals(orderId.toString(), row.get("event_key"));
        assertEquals("NEW", row.get("status"));
    }

    @Test
    void anUnsupportedEventTypeAndSchemaVersionAreStillRecordedAsTheyWereSeen() {
        String payload = authorizedJson(UUID.randomUUID(), orderId)
                .replace("\"PaymentAuthorized\"", "\"PaymentRefunded\"")
                .replace("\"schemaVersion\":1", "\"schemaVersion\":9");

        listener.onDeadLetterRecord(dltRecord(0, 6L, orderId.toString(), payload));

        Map<String, Object> row = singleRow();
        assertEquals("PaymentRefunded", row.get("event_type"), "the observed type is recorded, not validated");
        assertEquals(9, row.get("schema_version"));
        assertEquals(orderId, row.get("order_id"));
        assertNotNull(row.get("event_id"));
    }

    @Test
    void aPayloadWithNoEnvelopeFieldsStillYieldsARow() {
        listener.onDeadLetterRecord(dltRecord(0, 8L, null, "{\"unrelated\":true}"));

        Map<String, Object> row = singleRow();
        assertNull(row.get("event_id"));
        assertNull(row.get("event_key"));
        assertEquals("{\"unrelated\":true}", row.get("payload"));
    }

    // ---------- inspection API ----------

    @Test
    void listReturnsNewestFirstAndIsBoundedBySize() {
        for (int i = 0; i < 5; i++) {
            listener.onDeadLetterRecord(dltRecord(0, i, orderId.toString(), authorizedJson(UUID.randomUUID(), orderId)));
        }

        PageResponse<DeadLetterEventResponse> page = admin.list(null, null, null, null, null, 0, 2);

        assertEquals(2, page.getItems().size());
        assertEquals(5, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
        assertTrue(page.isHasNext());
    }

    @Test
    void listFiltersByEventIdEventTypeOrderIdAndPartition() {
        UUID wanted = UUID.randomUUID();
        UUID otherOrder = UUID.randomUUID();
        listener.onDeadLetterRecord(dltRecord(0, 1L, orderId.toString(), authorizedJson(wanted, orderId)));
        listener.onDeadLetterRecord(dltRecord(2, 2L, otherOrder.toString(), authorizedJson(UUID.randomUUID(), otherOrder)));

        assertEquals(1, admin.list(wanted, null, null, null, null, 0, 20).getTotalElements());
        assertEquals(2, admin.list(null, EventTypes.PAYMENT_AUTHORIZED, null, null, null, 0, 20).getTotalElements());
        assertEquals(1, admin.list(null, null, otherOrder, null, null, 0, 20).getTotalElements());
        assertEquals(1, admin.list(null, null, null, 2, null, 0, 20).getTotalElements());
        assertEquals(2, admin.list(null, null, null, null, DeadLetterStatus.NEW, 0, 20).getTotalElements());
        assertEquals(0, admin.list(null, null, null, null, DeadLetterStatus.REPLAYED, 0, 20).getTotalElements());
    }

    @Test
    void listRejectsAnUnboundedPageSize() {
        assertThrows(BadRequestException.class, () -> admin.list(null, null, null, null, null, 0, 101));
        assertThrows(BadRequestException.class, () -> admin.list(null, null, null, null, null, 0, 0));
        assertThrows(BadRequestException.class, () -> admin.list(null, null, null, null, null, -1, 20));
    }

    @Test
    void detailIncludesThePayloadAndUnknownIdIsNotFound() {
        UUID eventId = UUID.randomUUID();
        String payload = authorizedJson(eventId, orderId);
        listener.onDeadLetterRecord(dltRecord(0, 1L, orderId.toString(), payload));
        UUID id = deadLetterEvents.findAll().getFirst().getId();

        DeadLetterEventDetailResponse detail = admin.getById(id);
        assertEquals(payload, detail.payload());
        assertEquals(eventId, detail.event().eventId());

        assertThrows(ResourceNotFoundException.class, () -> admin.getById(UUID.randomUUID()));
    }

    // ---------- replay ----------

    @Test
    void replayRepublishesTheStoredKeyAndPayloadToTheOriginalTopicAndOnlyThenMarksReplayed() {
        UUID eventId = UUID.randomUUID();
        String payload = authorizedJson(eventId, orderId);
        listener.onDeadLetterRecord(dltRecord(0, 1L, orderId.toString(), payload));
        UUID id = deadLetterEvents.findAll().getFirst().getId();
        stubAck(KafkaTopics.PAYMENT_EVENTS_V1, 1, 77L);

        DeadLetterReplayResponse response = admin.replay(id);

        verify(kafkaTemplate).send(KafkaTopics.PAYMENT_EVENTS_V1, orderId.toString(), payload);
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1, response.replayedToTopic());
        assertEquals(eventId, response.eventId(), "replay must not mint a new eventId");
        assertEquals(77L, response.offset());

        DeadLetterEvent stored = deadLetterEvents.findById(id).orElseThrow();
        assertEquals(DeadLetterStatus.REPLAYED, stored.getStatus());
        assertNotNull(stored.getReplayedAt());
        assertEquals(1, stored.getReplayCount());
        assertNull(stored.getLastReplayError());
        assertEquals(payload, stored.getPayload(), "the stored payload must be untouched by replay");
    }

    @Test
    void aFailedSendLeavesTheRecordUnreplayedAndRecoverable() {
        String payload = authorizedJson(UUID.randomUUID(), orderId);
        listener.onDeadLetterRecord(dltRecord(0, 1L, orderId.toString(), payload));
        UUID id = deadLetterEvents.findAll().getFirst().getId();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        assertThrows(InternalServerException.class, () -> admin.replay(id));

        DeadLetterEvent stored = deadLetterEvents.findById(id).orElseThrow();
        assertEquals(DeadLetterStatus.REPLAY_FAILED, stored.getStatus());
        assertNull(stored.getReplayedAt(), "a failed replay must not stamp replayed_at");
        assertEquals(1, stored.getReplayCount());
        assertTrue(stored.getLastReplayError().contains("broker unavailable"));
        assertEquals(payload, stored.getPayload());
    }

    @Test
    void aReplayThatSucceedsAfterAFailureUsesTheSameStoredPayloadAndClearsTheError() {
        UUID eventId = UUID.randomUUID();
        String payload = authorizedJson(eventId, orderId);
        listener.onDeadLetterRecord(dltRecord(0, 1L, orderId.toString(), payload));
        UUID id = deadLetterEvents.findAll().getFirst().getId();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertThrows(InternalServerException.class, () -> admin.replay(id));

        stubAck(KafkaTopics.PAYMENT_EVENTS_V1, 0, 12L);
        DeadLetterReplayResponse response = admin.replay(id);

        verify(kafkaTemplate, org.mockito.Mockito.times(2))
                .send(KafkaTopics.PAYMENT_EVENTS_V1, orderId.toString(), payload);
        assertEquals(eventId, response.eventId());
        DeadLetterEvent stored = deadLetterEvents.findById(id).orElseThrow();
        assertEquals(DeadLetterStatus.REPLAYED, stored.getStatus());
        assertEquals(2, stored.getReplayCount(), "replay_count counts attempts, including the failed one");
        assertNull(stored.getLastReplayError());
    }

    @Test
    void replayOfAnUnknownRecordIsNotFoundAndPublishesNothing() {
        assertThrows(ResourceNotFoundException.class, () -> admin.replay(UUID.randomUUID()));
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void aRecordWhoseStoredPayloadIsUnreadableIsRejectedBeforeAnythingIsPublished() {
        listener.onDeadLetterRecord(dltRecord(0, 1L, orderId.toString(), "{this is not json"));
        UUID id = deadLetterEvents.findAll().getFirst().getId();

        BadRequestException rejected = assertThrows(BadRequestException.class, () -> admin.replay(id));

        assertTrue(rejected.getMessage().contains("not hold a replayable"));
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        assertEquals(DeadLetterStatus.NEW, deadLetterEvents.findById(id).orElseThrow().getStatus());
    }

    @Test
    void aRecordPointingAtAnUnsupportedOriginalTopicCannotBeReplayed() {
        // Simulates a row whose original_topic is not the payment stream: replay must refuse it
        // rather than become a general-purpose producer.
        jdbc.update("insert into dead_letter_event (id, original_topic, dlt_topic, dlt_partition, dlt_offset, "
                        + "dlt_timestamp, payload, first_seen_at, status, replay_count) "
                        + "values (?, 'attacker.topic', ?, 0, 1, now(), ?, now(), 'NEW', 0)",
                UUID.randomUUID(), KafkaTopics.PAYMENT_EVENTS_V1_DLT, authorizedJson(UUID.randomUUID(), orderId));
        UUID id = deadLetterEvents.findAll().getFirst().getId();

        BadRequestException rejected = assertThrows(BadRequestException.class, () -> admin.replay(id));

        assertTrue(rejected.getMessage().contains("not replayable"));
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(eq("attacker.topic"), anyString(), anyString());
    }

    // ---------- a record whose DLT headers are missing entirely ----------

    /** A bare record: hand-produced, or written by an older recoverer that stamped no headers. */
    private ConsumerRecord<String, String> bareRecord(int partition, long offset, String key, String value) {
        return new ConsumerRecord<>(KafkaTopics.PAYMENT_EVENTS_V1_DLT, partition, offset,
                System.currentTimeMillis(), TimestampType.CREATE_TIME, 0, 0, key, value,
                new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
    }

    @Test
    void aRecordWithNoDltHeadersIsStillCapturedWithItsOriginalTopicInferred() {
        UUID eventId = UUID.randomUUID();

        listener.onDeadLetterRecord(bareRecord(0, 3L, orderId.toString(), authorizedJson(eventId, orderId)));

        Map<String, Object> row = singleRow();
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1, row.get("original_topic"),
                "with no header to read, the topic is inferred from the DLT name: x.DLT implies x");
        // The header-derived columns degrade to null; nothing else does.
        assertNull(row.get("original_partition"));
        assertNull(row.get("original_offset"));
        assertNull(row.get("consumer_group"));
        assertNull(row.get("exception_class"));
        assertNull(row.get("exception_message"));
        assertNull(row.get("traceparent"));
        assertEquals(eventId, row.get("event_id"), "the payload is still parsed for what it does carry");
        assertEquals(0, row.get("dlt_partition"));
        assertEquals(3L, row.get("dlt_offset"));
        assertEquals("NEW", row.get("status"));
    }

    /** The inferred topic has to be the real one, or the allow-list would refuse to replay it. */
    @Test
    void aRecordWithAnInferredOriginalTopicIsStillReplayable() {
        String payload = authorizedJson(UUID.randomUUID(), orderId);
        listener.onDeadLetterRecord(bareRecord(0, 3L, orderId.toString(), payload));
        UUID id = deadLetterEvents.findAll().getFirst().getId();
        stubAck(KafkaTopics.PAYMENT_EVENTS_V1, 0, 9L);

        DeadLetterReplayResponse response = admin.replay(id);

        verify(kafkaTemplate).send(KafkaTopics.PAYMENT_EVENTS_V1, orderId.toString(), payload);
        assertEquals(KafkaTopics.PAYMENT_EVENTS_V1, response.replayedToTopic());
        assertEquals(DeadLetterStatus.REPLAYED, deadLetterEvents.findById(id).orElseThrow().getStatus());
    }

    // ---------- degenerate record values ----------

    @Test
    void aRecordWithANullValueIsStoredWithAnEmptyPayloadAndRefusedForReplay() {
        listener.onDeadLetterRecord(dltRecord(0, 4L, orderId.toString(), null));

        Map<String, Object> row = singleRow();
        assertEquals("", row.get("payload"), "payload is NOT NULL, so a null value is stored as empty");
        assertNull(row.get("event_id"));

        UUID id = deadLetterEvents.findAll().getFirst().getId();
        assertThrows(BadRequestException.class, () -> admin.replay(id),
                "there is nothing to put back on the topic");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        assertEquals(DeadLetterStatus.NEW, deadLetterEvents.findById(id).orElseThrow().getStatus());
    }

    @Test
    void aWhitespaceOnlyPayloadIsCapturedButNotReplayable() {
        listener.onDeadLetterRecord(dltRecord(0, 9L, orderId.toString(), "   "));

        UUID id = deadLetterEvents.findAll().getFirst().getId();
        assertEquals("   ", deadLetterEvents.findById(id).orElseThrow().getPayload());
        assertThrows(BadRequestException.class, () -> admin.replay(id));
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    /**
     * A pathological exception message must not be stored in full: one bad record should not be
     * able to write an unbounded blob into the inspection table on every redelivery.
     */
    @Test
    void anEnormousExceptionClassAndMessageAreTruncatedOnTheWayIn() {
        ConsumerRecord<String, String> record =
                dltRecord(0, 10L, orderId.toString(), authorizedJson(UUID.randomUUID(), orderId));
        record.headers().remove(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                "m".repeat(5_000).getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                "c".repeat(5_000).getBytes(StandardCharsets.UTF_8)));

        listener.onDeadLetterRecord(record);

        Map<String, Object> row = singleRow();
        assertEquals(4_000, ((String) row.get("exception_message")).length());
        assertEquals(4_000, ((String) row.get("exception_class")).length());
        assertNotNull(row.get("event_id"), "truncation must not cost us the rest of the row");
    }

    // ---------- replay is bounded by the configured send timeout ----------

    /**
     * A broker that accepts the send but never acknowledges it must not hang the request thread
     * forever. {@code order.dlt.replay.send-timeout} is what bounds it, so the wait is asserted
     * against a deliberately tiny timeout rather than the configured production one.
     */
    @Test
    void aReplayThatIsNeverAcknowledgedFailsWithinTheConfiguredSendTimeout() {
        String payload = authorizedJson(UUID.randomUUID(), orderId);
        listener.onDeadLetterRecord(dltRecord(0, 1L, orderId.toString(), payload));
        UUID id = deadLetterEvents.findAll().getFirst().getId();
        // A future that is never completed: the broker never answers.
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>());

        DeadLetterAdminService boundedByATinyTimeout = new DeadLetterAdminService(
                deadLetterEvents, parser, kafkaTemplate,
                new DeadLetterReplayProperties(Duration.ofMillis(250)), metrics);

        long startedAt = System.nanoTime();
        assertThrows(InternalServerException.class, () -> boundedByATinyTimeout.replay(id));
        Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

        assertTrue(waited.toSeconds() < 5,
                () -> "replay blocked for " + waited.toMillis() + "ms despite a 250ms send timeout");

        DeadLetterEvent stored = deadLetterEvents.findById(id).orElseThrow();
        assertEquals(DeadLetterStatus.REPLAY_FAILED, stored.getStatus());
        assertNull(stored.getReplayedAt(), "an unacknowledged send is not a replay");
        assertEquals(1, stored.getReplayCount());
        assertTrue(stored.getLastReplayError().contains("Timeout"),
                () -> "unexpected recorded error: " + stored.getLastReplayError());
        assertEquals(payload, stored.getPayload(), "the stored payload stays replayable");
    }

    // ---------- the schema is the last line of defence ----------

    @Test
    void theSchemaRefusesAnUnknownStatusANegativeReplayCountAndANullPayload() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertRaw("'BOGUS'", "0", "'{}'"), "status is constrained to the enum values");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertRaw("'NEW'", "-1", "'{}'"), "replay_count counts attempts and cannot be negative");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertRaw("'NEW'", "0", "null"), "payload is NOT NULL");

        assertEquals(0, deadLetterEvents.count());
    }

    /** Writes straight past the entity so the database's own constraints are what is being tested. */
    private void insertRaw(String status, String replayCount, String payload) {
        jdbc.update("insert into dead_letter_event (id, original_topic, dlt_topic, dlt_partition, "
                + "dlt_offset, dlt_timestamp, payload, first_seen_at, status, replay_count) values "
                + "('" + UUID.randomUUID() + "', '" + KafkaTopics.PAYMENT_EVENTS_V1 + "', '"
                + KafkaTopics.PAYMENT_EVENTS_V1_DLT + "', 0, 99, now(), " + payload + ", now(), "
                + status + ", " + replayCount + ")");
    }
}
