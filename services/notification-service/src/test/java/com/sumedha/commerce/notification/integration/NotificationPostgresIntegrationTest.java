package com.sumedha.commerce.notification.integration;

import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.notification.dto.response.NotificationResponse;
import com.sumedha.commerce.notification.entity.Notification;
import com.sumedha.commerce.notification.enums.NotificationChannel;
import com.sumedha.commerce.notification.enums.NotificationStatus;
import com.sumedha.commerce.notification.enums.NotificationType;
import com.sumedha.commerce.notification.messaging.NonRetryableEventException;
import com.sumedha.commerce.notification.messaging.NotificationEventProcessor;
import com.sumedha.commerce.notification.messaging.PaymentEventParser;
import com.sumedha.commerce.notification.repository.NotificationRepository;
import com.sumedha.commerce.notification.repository.ProcessedEventRepository;
import com.sumedha.commerce.notification.service.NotificationFactory;
import com.sumedha.commerce.notification.service.NotificationQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Notification persistence against real PostgreSQL: what one payment event writes, and - more
 * importantly - what a second, concurrent or failed delivery of it does not write.
 *
 * <p>No Kafka here: the listener is parked and the bootstrap address is a dead port, so this
 * exercises the transactional rules directly. Broker behaviour is covered by
 * {@code NotificationKafkaIntegrationTest}.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:59996",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
@Testcontainers
class NotificationPostgresIntegrationTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-11T10:15:30.123456Z");

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

    @Autowired private PaymentEventParser parser;
    @Autowired private NotificationEventProcessor processor;
    @Autowired private NotificationRepository notifications;
    @Autowired private ProcessedEventRepository processedEvents;
    @Autowired private NotificationQueryService queries;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    /** Spied so a failure can be injected after the claim; real otherwise. */
    @MockitoSpyBean private NotificationFactory factory;

    private final UUID orderId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void clearDatabase() {
        jdbc.update("delete from notification");
        jdbc.update("delete from processed_event");
    }

    // ---------- helpers ----------

    private String authorizedEvent(UUID eventId, UUID order) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId, EventTypes.PAYMENT_AUTHORIZED,
                EventEnvelope.SCHEMA_VERSION_V1, OCCURRED_AT,
                new PaymentAuthorizedEvent(paymentId, order, userId, new BigDecimal("59.97"), "USD")));
    }

    private String authorizedEvent(UUID eventId) {
        return authorizedEvent(eventId, orderId);
    }

    private String failedEvent(UUID eventId) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId, EventTypes.PAYMENT_FAILED,
                EventEnvelope.SCHEMA_VERSION_V1, OCCURRED_AT,
                new PaymentFailedEvent(paymentId, orderId, userId, "card declined")));
    }

    private NotificationEventProcessor.Result apply(String record) {
        return processor.process(parser.parse(record));
    }

    private int notificationCount() {
        return jdbc.queryForObject("select count(*) from notification", Integer.class);
    }

    private int markerCount() {
        return jdbc.queryForObject("select count(*) from processed_event", Integer.class);
    }

    // ---------- 1 + 2: one event, one durable notification ----------

    @Test
    void aPaymentAuthorizedEventCreatesOneDurableNotification() {
        UUID eventId = UUID.randomUUID();

        NotificationEventProcessor.Result result = apply(authorizedEvent(eventId));

        assertEquals(NotificationEventProcessor.Outcome.CREATED, result.outcome());
        Notification stored = notifications.findById(result.notificationId()).orElseThrow();
        assertEquals(eventId, stored.getEventId());
        assertEquals("PaymentAuthorized", stored.getEventType());
        assertEquals(orderId, stored.getOrderId());
        assertEquals(paymentId, stored.getPaymentId());
        assertEquals(userId, stored.getUserId());
        assertEquals(NotificationChannel.INTERNAL, stored.getChannel());
        assertEquals(NotificationType.PAYMENT_AUTHORIZED, stored.getNotificationType());
        assertEquals(NotificationStatus.CREATED, stored.getStatus());
        assertEquals(OCCURRED_AT, stored.getOccurredAt(), "the event's own timestamp survives at microsecond precision");
        assertEquals("Payment authorized for order " + orderId, stored.getSubject());
        assertTrue(stored.getMessage().contains("59.97 USD"), stored.getMessage());

        assertEquals(1, notificationCount());
        assertEquals(1, markerCount());
        assertEquals("PaymentAuthorized", processedEvents.findById(eventId).orElseThrow().getEventType());
        assertEquals(orderId, processedEvents.findById(eventId).orElseThrow().getOrderId());
    }

    @Test
    void aPaymentFailedEventCreatesOneDurableNotification() {
        UUID eventId = UUID.randomUUID();

        NotificationEventProcessor.Result result = apply(failedEvent(eventId));

        assertEquals(NotificationEventProcessor.Outcome.CREATED, result.outcome());
        Notification stored = notifications.findById(result.notificationId()).orElseThrow();
        assertEquals(NotificationType.PAYMENT_FAILED, stored.getNotificationType());
        assertEquals("PaymentFailed", stored.getEventType());
        assertEquals(NotificationStatus.CREATED, stored.getStatus());
        assertTrue(stored.getMessage().endsWith("Reason: card declined"), stored.getMessage());
        assertEquals(1, notificationCount());
        assertTrue(processedEvents.existsById(eventId));
    }

    // ---------- 3: marker and notification are one unit ----------

    /**
     * A notification insert that the database rejects must take the claim down with it. The
     * conflicting row here is planted without a marker - a state the application never produces -
     * so the only thing that can fail is the insert, and it fails in PostgreSQL itself.
     */
    @Test
    void whenTheNotificationInsertFailsTheClaimRollsBackWithIt() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("insert into notification (notification_id, event_id, event_type, order_id, payment_id, "
                        + "user_id, channel, notification_type, subject, message, status, occurred_at, created_at, "
                        + "updated_at) values (?, ?, 'PaymentAuthorized', ?, ?, ?, 'INTERNAL', 'PAYMENT_AUTHORIZED', "
                        + "'planted', 'planted', 'CREATED', now(), now(), now())",
                UUID.randomUUID(), eventId, orderId, paymentId, userId);

        assertThrows(DataIntegrityViolationException.class, () -> apply(authorizedEvent(eventId)));

        assertFalse(processedEvents.existsById(eventId),
                "a claim whose notification did not commit must not survive to swallow the retry");
        assertEquals(1, notificationCount(), "only the planted row exists");
    }

    @Test
    void aFailureAfterTheClaimLeavesNothingAndTheRedeliveryIsProcessed() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated failure after the event was claimed");
            }
            return invocation.callRealMethod();
        }).when(factory).create(any());

        assertThrows(IllegalStateException.class, () -> apply(failedEvent(eventId)));
        assertEquals(0, markerCount());
        assertEquals(0, notificationCount());

        assertEquals(NotificationEventProcessor.Outcome.CREATED, apply(failedEvent(eventId)).outcome(),
                "the redelivery is processed, not mistaken for a duplicate");
        assertEquals(1, markerCount());
        assertEquals(1, notificationCount());
    }

    // ---------- 4: duplicates ----------

    @Test
    void redeliveringTheSameEventCreatesNoSecondNotification() {
        UUID eventId = UUID.randomUUID();
        String record = authorizedEvent(eventId);

        assertEquals(NotificationEventProcessor.Outcome.CREATED, apply(record).outcome());
        assertEquals(NotificationEventProcessor.Outcome.DUPLICATE, apply(record).outcome());
        assertEquals(NotificationEventProcessor.Outcome.DUPLICATE, apply(record).outcome());

        assertEquals(1, notificationCount());
        assertEquals(1, notifications.countByEventId(eventId));
        assertEquals(1, markerCount());
    }

    /** Same eventId, different body (e.g. a hand-edited replay): the eventId alone decides. */
    @Test
    void theEventIdAloneDecidesWhatIsADuplicate() {
        UUID eventId = UUID.randomUUID();

        apply(authorizedEvent(eventId));
        assertEquals(NotificationEventProcessor.Outcome.DUPLICATE, apply(failedEvent(eventId)).outcome());

        assertEquals(1, notificationCount());
        assertEquals(NotificationType.PAYMENT_AUTHORIZED,
                notifications.findAll().getFirst().getNotificationType());
    }

    @Test
    void distinctEventsForTheSameOrderAreNotDuplicates() {
        apply(authorizedEvent(UUID.randomUUID()));
        apply(failedEvent(UUID.randomUUID()));

        assertEquals(2, notificationCount());
        assertEquals(2, markerCount());
    }

    // ---------- 5: concurrency ----------

    private List<String> applyConcurrently(List<String> records) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(records.size());
        ExecutorService pool = Executors.newFixedThreadPool(records.size());
        List<Callable<String>> attempts = new ArrayList<>();
        for (String record : records) {
            attempts.add(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                try {
                    return apply(record).outcome().name();
                } catch (RuntimeException failure) {
                    return failure.getClass().getSimpleName();
                }
            });
        }

        List<String> outcomes = new ArrayList<>();
        try {
            for (Future<String> future : pool.invokeAll(attempts)) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    /**
     * Concurrent deliveries of one eventId - a rebalance racing a redelivery. Exactly one may write,
     * and the rest must come back as clean duplicates rather than errors the container would retry
     * or dead-letter.
     */
    @Test
    void concurrentDeliveriesOfTheSameEventCreateExactlyOneNotification() throws Exception {
        UUID eventId = UUID.randomUUID();
        String record = authorizedEvent(eventId);

        List<String> outcomes = applyConcurrently(List.of(record, record, record, record, record, record, record, record));

        assertEquals(1, outcomes.stream().filter("CREATED"::equals).count(),
                () -> "exactly one delivery may create; outcomes were " + outcomes);
        assertEquals(7, outcomes.stream().filter("DUPLICATE"::equals).count(),
                () -> "the others are acknowledged duplicates, not failures; outcomes were " + outcomes);
        assertEquals(1, notifications.countByEventId(eventId));
        assertEquals(1, notificationCount());
        assertEquals(1, markerCount());
    }

    @Test
    void concurrentDistinctEventsAllCreateTheirNotification() throws Exception {
        List<String> records = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            records.add(i % 2 == 0 ? authorizedEvent(UUID.randomUUID()) : failedEvent(UUID.randomUUID()));
        }

        List<String> outcomes = applyConcurrently(records);

        assertEquals(6, outcomes.stream().filter("CREATED"::equals).count(), () -> "outcomes were " + outcomes);
        assertEquals(6, notificationCount());
        assertEquals(6, markerCount());
    }

    // ---------- 6: rollback removes both ----------

    /**
     * The processor joins an enclosing transaction; if that rolls back after both writes, neither
     * the marker nor the notification may remain.
     */
    @Test
    void aRolledBackTransactionRemovesBothTheMarkerAndTheNotification() {
        UUID eventId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            apply(authorizedEvent(eventId));
            assertTrue(processedEvents.existsById(eventId), "claimed inside the transaction");
            assertEquals(1, notifications.countByEventId(eventId), "and the notification is written in it");
            throw new IllegalStateException("simulated failure after both writes, before commit");
        }));

        assertFalse(processedEvents.existsById(eventId));
        assertEquals(0, notifications.countByEventId(eventId));
        assertEquals(0, notificationCount());
        assertEquals(0, markerCount());

        assertEquals(NotificationEventProcessor.Outcome.CREATED, apply(authorizedEvent(eventId)).outcome(),
                "after the rollback the event is still processable");
    }

    // ---------- 7: unreadable records create nothing ----------

    @Test
    void malformedUnsupportedOrIncompleteRecordsCreateNothing() {
        UUID eventId = UUID.randomUUID();
        String valid = authorizedEvent(eventId);

        assertThrows(NonRetryableEventException.class, () -> apply("{this is not json"));
        assertThrows(NonRetryableEventException.class, () -> apply(""));
        assertThrows(NonRetryableEventException.class, () -> apply("{}"));
        assertThrows(NonRetryableEventException.class,
                () -> apply(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertThrows(NonRetryableEventException.class,
                () -> apply(valid.replace("\"PaymentAuthorized\"", "\"PaymentCaptured\"")));
        assertThrows(NonRetryableEventException.class,
                () -> apply(valid.replace("\"orderId\":\"" + orderId + "\"", "\"orderId\":null")));

        assertEquals(0, notificationCount());
        assertEquals(0, markerCount());
    }

    // ---------- 8: schema, constraints and query paths ----------

    @Test
    void theMigrationAppliedAndCreatedTheExpectedTables() {
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class));
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertTrue(tables.containsAll(List.of("notification", "processed_event", "flyway_schema_history")), tables.toString());
    }

    @Test
    void theQueryIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where tablename = 'notification'", String.class);

        assertTrue(indexes.containsAll(List.of(
                "notification_pkey",
                "uq_notification_event_channel_type",
                "idx_notification_created_at",
                "idx_notification_order_id",
                "idx_notification_user_id",
                "idx_notification_type_status")), indexes.toString());
        assertTrue(jdbc.queryForList("select indexname from pg_indexes where tablename = 'processed_event'", String.class)
                .contains("processed_event_pkey"));
    }

    @Test
    void theDatabaseRejectsStatusesAndChannelsTheModelDoesNotHave() {
        String insert = "insert into notification (notification_id, event_id, event_type, order_id, payment_id, "
                + "user_id, channel, notification_type, subject, message, status, occurred_at, created_at, "
                + "updated_at) values (?, ?, 'PaymentAuthorized', ?, ?, ?, ?, ?, 's', 'm', ?, now(), now(), now())";

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(),
                UUID.randomUUID(), orderId, paymentId, userId, "INTERNAL", "PAYMENT_AUTHORIZED", "SENT"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(),
                UUID.randomUUID(), orderId, paymentId, userId, "EMAIL", "PAYMENT_AUTHORIZED", "CREATED"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(),
                UUID.randomUUID(), orderId, paymentId, userId, "INTERNAL", "ORDER_SHIPPED", "CREATED"));
        assertEquals(0, notificationCount());
    }

    @Test
    void thereAreNoCrossServiceForeignKeys() {
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where table_schema = 'public' and constraint_type = 'FOREIGN KEY'", Integer.class));
    }

    /** The optional-filter JPQL binds typed nulls on PostgreSQL, pages correctly, and lists newest first. */
    @Test
    void listingFiltersPagesAndOrdersNewestFirstOnPostgres() {
        UUID otherOrder = UUID.randomUUID();
        UUID oldest = apply(authorizedEvent(UUID.randomUUID())).notificationId();
        UUID middle = apply(failedEvent(UUID.randomUUID())).notificationId();
        UUID newest = apply(authorizedEvent(UUID.randomUUID(), otherOrder)).notificationId();
        setCreatedAt(oldest, "2026-09-11T10:00:00Z");
        setCreatedAt(middle, "2026-09-11T11:00:00Z");
        setCreatedAt(newest, "2026-09-11T12:00:00Z");

        PageResponse<NotificationResponse> all = queries.list(null, null, null, null, null, 0, 2);
        assertEquals(List.of(newest, middle), all.getItems().stream().map(NotificationResponse::notificationId).toList());
        assertEquals(3, all.getTotalElements());
        assertTrue(all.isHasNext());

        assertEquals(List.of(oldest), queries.list(null, null, null, null, null, 1, 2)
                .getItems().stream().map(NotificationResponse::notificationId).toList());

        assertEquals(List.of(middle, oldest), queries.listByOrder(orderId, 0, 20)
                .getItems().stream().map(NotificationResponse::notificationId).toList());
        assertEquals(List.of(middle), queries.list(orderId, userId, "PaymentFailed",
                        NotificationType.PAYMENT_FAILED, NotificationStatus.CREATED, 0, 20)
                .getItems().stream().map(NotificationResponse::notificationId).toList());
        assertEquals(2, queries.list(null, null, null, NotificationType.PAYMENT_AUTHORIZED, null, 0, 20)
                .getTotalElements());
        assertEquals(0, queries.list(UUID.randomUUID(), null, null, null, null, 0, 20).getTotalElements());
    }

    private void setCreatedAt(UUID notificationId, String instant) {
        jdbc.update("update notification set created_at = ? where notification_id = ?",
                Timestamp.from(Instant.parse(instant)), notificationId);
    }
}
