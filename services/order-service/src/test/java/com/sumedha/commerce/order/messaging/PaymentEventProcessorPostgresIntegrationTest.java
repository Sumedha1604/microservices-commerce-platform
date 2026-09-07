package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.order.dto.request.CreateOrderItemRequest;
import com.sumedha.commerce.order.dto.request.CreateOrderRequest;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.enums.OrderStatus;
import com.sumedha.commerce.order.repository.OrderRepository;
import com.sumedha.commerce.order.repository.ProcessedEventRepository;
import com.sumedha.commerce.order.service.OrderService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/**
 * The processor against real PostgreSQL: state transitions, the {@code processed_event} marker,
 * and - the point of using a real database - that the transition and the marker really do commit
 * or roll back together.
 *
 * <p>No Kafka here: the listener is stopped and the bootstrap address is a dead port, so this
 * test cannot touch a developer's broker.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:59997"
})
@Testcontainers
class PaymentEventProcessorPostgresIntegrationTest {

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

    @Autowired private PaymentEventProcessor processor;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    /** Spy so one specific marker insert can be made to fail without disturbing other tests. */
    @MockitoSpyBean private ProcessedEventRepository processedEvents;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void clearDatabase() {
        jdbc.update("delete from processed_event");
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
    }

    // ---------- helpers ----------

    private UUID createPendingOrder() {
        return orderService.create(new CreateOrderRequest(userId, "USD", List.of(
                new CreateOrderItemRequest(UUID.randomUUID(), "Widget", "sku-1", new BigDecimal("19.99"), 3)))).id();
    }

    private PaymentEvent.Authorized authorized(UUID eventId, UUID orderId) {
        return new PaymentEvent.Authorized(eventId, new PaymentAuthorizedEvent(
                UUID.randomUUID(), orderId, userId, new BigDecimal("59.97"), "USD"));
    }

    private PaymentEvent.Failed failed(UUID eventId, UUID orderId) {
        return new PaymentEvent.Failed(eventId, new PaymentFailedEvent(
                UUID.randomUUID(), orderId, userId, "card declined"));
    }

    private OrderStatus statusOf(UUID orderId) {
        return orders.findById(orderId).map(Order::getStatus).orElseThrow();
    }

    private int markerCount() {
        return jdbc.queryForObject("select count(*) from processed_event", Integer.class);
    }

    private Map<String, Object> marker(UUID eventId) {
        return jdbc.queryForMap("select * from processed_event where event_id = ?", eventId);
    }

    // ---------- schema ----------

    @Test
    void flywayCreatesTheProcessedEventTable() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertTrue(tables.contains("processed_event"));

        List<String> primaryKey = jdbc.queryForList(
                "select kcu.column_name from information_schema.table_constraints tc "
                        + "join information_schema.key_column_usage kcu on tc.constraint_name = kcu.constraint_name "
                        + "where tc.table_name = 'processed_event' and tc.constraint_type = 'PRIMARY KEY'",
                String.class);
        assertEquals(List.of("event_id"), primaryKey);
    }

    @Test
    void processedEventHasNoForeignKeyToOrders() {
        List<String> foreignKeys = jdbc.queryForList(
                "select constraint_name from information_schema.table_constraints "
                        + "where constraint_type = 'FOREIGN KEY' and table_name = 'processed_event'", String.class);
        assertTrue(foreignKeys.isEmpty(), "the dedup marker must not depend on the order row's lifecycle");
    }

    // ---------- transitions ----------

    @Test
    void authorizedMovesPendingToConfirmedAndWritesTheMarker() {
        UUID orderId = createPendingOrder();
        UUID eventId = UUID.randomUUID();

        assertEquals(PaymentEventProcessor.Outcome.APPLIED, processor.process(authorized(eventId, orderId)));

        assertEquals(OrderStatus.CONFIRMED, statusOf(orderId));
        Map<String, Object> marker = marker(eventId);
        assertEquals("PaymentAuthorized", marker.get("event_type"));
        assertEquals(orderId, marker.get("order_id"));
        assertEquals(1, markerCount());
    }

    @Test
    void failedMovesPendingToCancelledAndWritesTheMarker() {
        UUID orderId = createPendingOrder();
        UUID eventId = UUID.randomUUID();

        assertEquals(PaymentEventProcessor.Outcome.APPLIED, processor.process(failed(eventId, orderId)));

        assertEquals(OrderStatus.CANCELLED, statusOf(orderId));
        assertEquals("PaymentFailed", marker(eventId).get("event_type"));
    }

    @Test
    void failedMovesConfirmedToCancelledAndWritesTheMarker() {
        UUID orderId = createPendingOrder();
        processor.process(authorized(UUID.randomUUID(), orderId));
        assertEquals(OrderStatus.CONFIRMED, statusOf(orderId));

        UUID eventId = UUID.randomUUID();
        assertEquals(PaymentEventProcessor.Outcome.APPLIED, processor.process(failed(eventId, orderId)));

        assertEquals(OrderStatus.CANCELLED, statusOf(orderId));
        assertEquals(2, markerCount());
    }

    // ---------- idempotency ----------

    @Test
    void redeliveryOfTheSameEventIdChangesNothingAndWritesNoSecondMarker() {
        UUID orderId = createPendingOrder();
        UUID eventId = UUID.randomUUID();
        processor.process(authorized(eventId, orderId));

        // same eventId again - what happens when the DB committed but the offset did not
        assertEquals(PaymentEventProcessor.Outcome.DUPLICATE, processor.process(authorized(eventId, orderId)));

        assertEquals(OrderStatus.CONFIRMED, statusOf(orderId));
        assertEquals(1, markerCount());
    }

    @Test
    void aDifferentEventIdAuthorizingAnAlreadyConfirmedOrderSucceedsAndIsRecorded() {
        UUID orderId = createPendingOrder();
        processor.process(authorized(UUID.randomUUID(), orderId));
        UUID secondEventId = UUID.randomUUID();

        assertEquals(PaymentEventProcessor.Outcome.ALREADY_IN_TARGET_STATE,
                processor.process(authorized(secondEventId, orderId)));

        assertEquals(OrderStatus.CONFIRMED, statusOf(orderId));
        assertEquals(2, markerCount());
        assertEquals(orderId, marker(secondEventId).get("order_id"));
    }

    @Test
    void aDifferentEventIdFailingAnAlreadyCancelledOrderSucceedsAndIsRecorded() {
        UUID orderId = createPendingOrder();
        processor.process(failed(UUID.randomUUID(), orderId));
        UUID secondEventId = UUID.randomUUID();

        assertEquals(PaymentEventProcessor.Outcome.ALREADY_IN_TARGET_STATE,
                processor.process(failed(secondEventId, orderId)));

        assertEquals(OrderStatus.CANCELLED, statusOf(orderId));
        assertEquals(2, markerCount());
    }

    // ---------- non-retryable semantics ----------

    @Test
    void authorizingACancelledOrderFailsAndLeavesNoMarker() {
        UUID orderId = createPendingOrder();
        processor.process(failed(UUID.randomUUID(), orderId));
        UUID eventId = UUID.randomUUID();

        assertThrows(NonRetryableEventException.class, () -> processor.process(authorized(eventId, orderId)));

        assertEquals(OrderStatus.CANCELLED, statusOf(orderId));
        assertEquals(1, markerCount(), "only the earlier PaymentFailed marker may exist");
    }

    @Test
    void anUnknownOrderFailsAndLeavesNoMarker() {
        UUID eventId = UUID.randomUUID();

        assertThrows(NonRetryableEventException.class,
                () -> processor.process(authorized(eventId, UUID.randomUUID())));

        assertEquals(0, markerCount());
    }

    // ---------- atomicity ----------

    @Test
    void anOuterRollbackDiscardsBothTheTransitionAndTheMarker() {
        UUID orderId = createPendingOrder();
        UUID eventId = UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            processor.process(authorized(eventId, orderId));
            status.setRollbackOnly();
        });

        assertEquals(OrderStatus.PENDING, statusOf(orderId), "the transition must not survive the rollback");
        assertEquals(0, markerCount(), "the marker must not survive the rollback");
    }

    @Test
    void aFailingMarkerInsertRollsBackTheOrderTransition() {
        UUID orderId = createPendingOrder();
        UUID eventId = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("marker insert failed"))
                .when(processedEvents).saveAndFlush(argThat(marker -> marker.getEventId().equals(eventId)));

        assertThrows(DataIntegrityViolationException.class, () -> processor.process(authorized(eventId, orderId)));

        assertEquals(OrderStatus.PENDING, statusOf(orderId),
                "if the marker cannot be written the order must not be left confirmed");
        assertEquals(0, markerCount());
    }
}
