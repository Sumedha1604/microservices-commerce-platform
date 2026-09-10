package com.sumedha.commerce.order.messaging;

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
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * The saga's write side against real PostgreSQL: a cancellation and its compensation promise are
 * one atomic fact, and the publisher can only ever weaken that into "sent at least once".
 *
 * <p>No broker: the publisher is a mock so acknowledgement and failure can both be forced, and
 * the scheduler is disabled so nothing races the assertions.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:59997",
        // The batch processor is driven by hand here; a background poll would race every assertion.
        "order.outbox.enabled=false"
})
@Testcontainers
class OrderCompensationOutboxPostgresIntegrationTest {

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

    /** Stands in for the broker so acks and failures are both deterministic. */
    @MockitoBean
    private OrderEventPublisher publisher;

    /** Spied so an outbox write can be made to fail inside the business transaction. */
    @MockitoSpyBean
    private OrderOutboxEventRepository outboxEvents;

    @Autowired private PaymentEventProcessor processor;
    @Autowired private OrderOutboxBatchProcessor batchProcessor;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void resetState() {
        reset(publisher);
        jdbc.update("delete from order_outbox_event");
        jdbc.update("delete from processed_event");
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
    }

    // ---------- helpers ----------

    private UUID createPendingOrder() {
        return orderService.create(new CreateOrderRequest(userId, "USD", List.of(
                new CreateOrderItemRequest(UUID.randomUUID(), "Widget", "sku-1",
                        new BigDecimal("19.99"), 3)))).id();
    }

    private PaymentEvent.Failed failed(UUID orderId) {
        return new PaymentEvent.Failed(UUID.randomUUID(),
                new PaymentFailedEvent(UUID.randomUUID(), orderId, userId, "card declined"));
    }

    private PaymentEvent.Authorized authorized(UUID orderId) {
        return new PaymentEvent.Authorized(UUID.randomUUID(),
                new PaymentAuthorizedEvent(UUID.randomUUID(), orderId, userId,
                        new BigDecimal("59.97"), "USD"));
    }

    private List<OrderOutboxEvent> compensationRows() {
        return outboxEvents.findAll();
    }

    private void stubAck() {
        SendResult<String, String> result = mock(SendResult.class);
        when(result.getRecordMetadata()).thenReturn(
                new RecordMetadata(new TopicPartition(KafkaTopics.ORDER_COMPENSATION_V1, 0), 1L, 0, 0L, 0, 0));
        when(publisher.publish(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(result));
    }

    /** Acknowledges every send and records the exact payload bytes handed to the broker. */
    private List<String> recordSendsAndAck() {
        List<String> sent = new CopyOnWriteArrayList<>();
        SendResult<String, String> result = mock(SendResult.class);
        when(result.getRecordMetadata()).thenReturn(
                new RecordMetadata(new TopicPartition(KafkaTopics.ORDER_COMPENSATION_V1, 0), 1L, 0, 0L, 0, 0));
        when(publisher.publish(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(2));
            return CompletableFuture.completedFuture(result);
        });
        return sent;
    }

    private void stubBrokerDown() {
        when(publisher.publish(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
    }

    // ---------- cancellation produces exactly one compensation ----------

    @Test
    void paymentFailedCancelsTheOrderAndQueuesExactlyOnePendingCompensation() {
        UUID orderId = createPendingOrder();

        processor.process(failed(orderId));

        assertEquals(OrderStatus.CANCELLED, orders.findById(orderId).orElseThrow().getStatus());
        List<OrderOutboxEvent> rows = compensationRows();
        assertEquals(1, rows.size());
        OrderOutboxEvent row = rows.getFirst();
        assertEquals(OutboxEventStatus.PENDING, row.getStatus());
        assertEquals(EventTypes.INVENTORY_RELEASE_REQUESTED, row.getEventType());
        assertEquals(KafkaTopics.ORDER_COMPENSATION_V1, row.getTopic());
        assertEquals(orderId, row.getAggregateId());
        assertEquals(orderId.toString(), row.getEventKey());
        assertEquals(0, row.getAttemptCount());
        assertNull(row.getPublishedAt());
    }

    @Test
    void paymentAuthorizedConfirmsTheOrderAndQueuesNoCompensationAtAll() {
        UUID orderId = createPendingOrder();

        processor.process(authorized(orderId));

        assertEquals(OrderStatus.CONFIRMED, orders.findById(orderId).orElseThrow().getStatus());
        assertEquals(0, compensationRows().size(),
                "a successful payment must never ask for the reservation back");
    }

    @Test
    void aDuplicatePaymentFailedDeliveryDoesNotQueueASecondCompensation() {
        UUID orderId = createPendingOrder();
        PaymentEvent.Failed event = failed(orderId);

        assertEquals(PaymentEventProcessor.Outcome.APPLIED, processor.process(event));
        assertEquals(PaymentEventProcessor.Outcome.DUPLICATE, processor.process(event));
        assertEquals(PaymentEventProcessor.Outcome.DUPLICATE, processor.process(event));

        assertEquals(1, compensationRows().size(),
                "three deliveries of one payment failure owe inventory exactly one release");
    }

    /**
     * A <em>different</em> payment failure arriving for an order that is already cancelled did not
     * cancel anything, so it must not queue a second release either.
     */
    @Test
    void aDistinctFailureForAnAlreadyCancelledOrderQueuesNoSecondCompensation() {
        UUID orderId = createPendingOrder();
        processor.process(failed(orderId));

        assertEquals(PaymentEventProcessor.Outcome.ALREADY_IN_TARGET_STATE, processor.process(failed(orderId)));

        assertEquals(1, compensationRows().size());
    }

    /**
     * The same PaymentFailed handed to several workers at once (a rebalance mid-processing). They
     * can all pass the duplicate check, but only one transaction can commit the cancellation; the
     * others fail on the order's version or the marker's key and roll their outbox rows back.
     */
    @Test
    void concurrentDeliveriesOfTheSamePaymentFailureQueueExactlyOneCompensation() throws Exception {
        UUID orderId = createPendingOrder();
        PaymentEvent.Failed event = failed(orderId);

        int workers = 4;
        CyclicBarrier startTogether = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Callable<String>> attempts = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            attempts.add(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                try {
                    return processor.process(event).name();
                } catch (RuntimeException lostTheRace) {
                    return "ROLLED_BACK";
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

        assertEquals(1, outcomes.stream().filter("APPLIED"::equals).count(),
                () -> "exactly one worker may cancel; outcomes were " + outcomes);
        assertEquals(1, compensationRows().size(), () -> "one cancellation, one compensation; outcomes were " + outcomes);
        assertEquals(OrderStatus.CANCELLED, orders.findById(orderId).orElseThrow().getStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_event", Integer.class));

        // The container retries a rolled-back worker; that retry is now a plain duplicate.
        assertEquals(PaymentEventProcessor.Outcome.DUPLICATE, processor.process(event));
        assertEquals(1, compensationRows().size());
    }

    // ---------- atomicity ----------

    @Test
    void anOutboxInsertFailureRollsBackTheCancellation() {
        UUID orderId = createPendingOrder();
        doThrow(new DataIntegrityViolationException("outbox write rejected"))
                .when(outboxEvents).saveAndFlush(any());

        assertThrows(DataIntegrityViolationException.class, () -> processor.process(failed(orderId)));

        assertEquals(OrderStatus.PENDING, orders.findById(orderId).orElseThrow().getStatus(),
                "an order must never be cancelled without the promise to release its inventory");
        assertEquals(0, compensationRows().size());
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_event", Integer.class),
                "and no processed marker may survive either, or the retry would be swallowed");
    }

    @Test
    void anOuterTransactionRollbackDiscardsBothTheCancellationAndTheCompensation() {
        UUID orderId = createPendingOrder();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            processor.process(failed(orderId));
            throw new IllegalStateException("caller fails after the processor returned");
        }));

        assertEquals(OrderStatus.PENDING, orders.findById(orderId).orElseThrow().getStatus());
        assertEquals(0, compensationRows().size(), "the compensation joined the caller's transaction");
    }

    // ---------- publishing ----------

    @Test
    void publishingMarksTheRowPublishedOnlyAfterTheBrokerAcknowledges() {
        UUID orderId = createPendingOrder();
        processor.process(failed(orderId));
        stubAck();

        assertEquals(1, batchProcessor.publishNextBatch());

        OrderOutboxEvent row = compensationRows().getFirst();
        assertEquals(OutboxEventStatus.PUBLISHED, row.getStatus());
        assertNotNull(row.getPublishedAt());
        assertNull(row.getLastError());
    }

    @Test
    void aBrokerFailureLeavesTheRowPendingWithItsPayloadAndEventIdUntouched() {
        UUID orderId = createPendingOrder();
        processor.process(failed(orderId));
        OrderOutboxEvent before = compensationRows().getFirst();
        stubBrokerDown();

        batchProcessor.publishNextBatch();

        OrderOutboxEvent after = compensationRows().getFirst();
        assertEquals(OutboxEventStatus.PENDING, after.getStatus(), "an unacknowledged send is not a publish");
        assertEquals(before.getEventId(), after.getEventId());
        assertEquals(before.getPayload(), after.getPayload());
        assertEquals(1, after.getAttemptCount());
        assertNotNull(after.getNextAttemptAt(), "the retry is scheduled, not abandoned");
        assertTrue(after.getLastError().contains("broker unavailable"));
        assertEquals(OrderStatus.CANCELLED, orders.findById(orderId).orElseThrow().getStatus(),
                "Kafka being down must not un-cancel the order");
    }

    /**
     * The republish keeps the identity the business transaction minted. That is precisely what
     * lets inventory-service recognise a redelivery instead of releasing the stock twice.
     */
    @Test
    void aRetryAfterFailureRepublishesTheSameEventIdAndTheSameBytes() {
        UUID orderId = createPendingOrder();
        processor.process(failed(orderId));
        OrderOutboxEvent original = compensationRows().getFirst();

        stubBrokerDown();
        batchProcessor.publishNextBatch();
        // Clear the backoff so the retry is eligible immediately.
        jdbc.update("update order_outbox_event set next_attempt_at = null");

        stubAck();
        assertEquals(1, batchProcessor.publishNextBatch());

        OrderOutboxEvent published = compensationRows().getFirst();
        assertEquals(OutboxEventStatus.PUBLISHED, published.getStatus());
        assertEquals(original.getEventId(), published.getEventId(),
                "the eventId is minted once and reused forever");
        assertEquals(original.getPayload(), published.getPayload());
        assertEquals(1, published.getAttemptCount(),
                "attempt_count records failed attempts only - the one failure above - matching the "
                        + "payment outbox, where it exists to drive the backoff rather than to count sends");
        assertNull(published.getLastError(), "a success clears the previous error");
    }

    /**
     * The crash window: the broker acknowledged the send, but the transaction that would have
     * marked the row PUBLISHED never committed. The row must still be PENDING, and the next cycle
     * must send the identical event again - same eventId, same bytes - because that identity is
     * the only thing inventory-service can deduplicate on. This is at-least-once, not exactly-once.
     */
    @Test
    void aCrashAfterTheBrokerAckButBeforeThePublishedCommitRepublishesTheIdenticalEvent() {
        UUID orderId = createPendingOrder();
        processor.process(failed(orderId));
        OrderOutboxEvent original = compensationRows().getFirst();
        List<String> sent = recordSendsAndAck();

        // The send is acknowledged inside the publisher's transaction, then the process "dies":
        // the enclosing transaction rolls back instead of committing the PUBLISHED update.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(1, batchProcessor.publishNextBatch());
            status.setRollbackOnly();
        });

        OrderOutboxEvent afterCrash = compensationRows().getFirst();
        assertEquals(OutboxEventStatus.PENDING, afterCrash.getStatus(),
                "an acknowledged send whose PUBLISHED update was lost is still owed");
        assertNull(afterCrash.getPublishedAt());
        assertEquals(1, sent.size(), "the first copy genuinely reached the broker");

        assertEquals(1, batchProcessor.publishNextBatch(), "the restarted publisher claims the same row");

        assertEquals(2, sent.size(), "the event was sent twice - at-least-once, never claimed as exactly-once");
        assertEquals(sent.get(0), sent.get(1), "the republish is byte-identical");
        assertEquals(original.getPayload(), sent.get(1));
        assertTrue(sent.get(1).contains(original.getEventId().toString()),
                "and carries the eventId minted by the business transaction");
        assertEquals(OutboxEventStatus.PUBLISHED, compensationRows().getFirst().getStatus());
    }

    // ---------- ordering ----------

    @Test
    void anEarlierPendingRowHoldsBackLaterRowsForTheSameOrderButNotForOtherOrders() {
        UUID blockedOrder = createPendingOrder();
        UUID otherOrder = createPendingOrder();
        processor.process(failed(blockedOrder));
        processor.process(failed(otherOrder));
        // A second, later compensation for the same order, as a future event type would produce.
        insertLaterCompensationFor(blockedOrder);

        List<OrderOutboxEvent> claimed = claimBatch();

        assertEquals(2, claimed.size(), "one row per aggregate, and unrelated orders are not blocked");
        assertEquals(1, claimed.stream().filter(r -> r.getAggregateId().equals(blockedOrder)).count());
        assertEquals(1, claimed.stream().filter(r -> r.getAggregateId().equals(otherOrder)).count());
        OrderOutboxEvent blocked = claimed.stream()
                .filter(r -> r.getAggregateId().equals(blockedOrder)).findFirst().orElseThrow();
        assertEquals("InventoryReleaseRequested", blocked.getEventType());
        assertTrue(blocked.getCreatedAt().isBefore(
                        outboxEvents.findByAggregateIdOrderByCreatedAtAscIdAsc(blockedOrder).get(1).getCreatedAt()),
                "the earliest pending row for the order is the one claimed");
    }

    @Test
    void theSuccessorIsClaimedOnlyOnceItsPredecessorIsPublished() {
        UUID orderId = createPendingOrder();
        processor.process(failed(orderId));
        insertLaterCompensationFor(orderId);
        stubAck();

        assertEquals(1, batchProcessor.publishNextBatch(), "only the predecessor is eligible");
        assertEquals(1, batchProcessor.publishNextBatch(), "now the successor is");

        assertTrue(compensationRows().stream()
                .allMatch(r -> r.getStatus() == OutboxEventStatus.PUBLISHED));
    }

    /** Two publishers must never claim one row - SKIP LOCKED is what guarantees it. */
    @Test
    void concurrentPublishersNeverClaimTheSameRow() throws Exception {
        for (int i = 0; i < 8; i++) {
            processor.process(failed(createPendingOrder()));
        }
        List<String> published = recordSendsAndAck();

        int workers = 4;
        CyclicBarrier startTogether = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            attempts.add(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return batchProcessor.publishNextBatch();
            });
        }
        try {
            for (Future<Integer> future : pool.invokeAll(attempts)) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(8, published.size(), "each row must be published exactly once across all workers");
        assertEquals(8, published.stream().distinct().count(), "no payload may be published twice");
        assertEquals(8, compensationRows().stream()
                .filter(r -> r.getStatus() == OutboxEventStatus.PUBLISHED).count());
    }

    /**
     * A second compensation row for the same order, written directly so the ordering guard can be
     * exercised without inventing a second production event type.
     */
    private void insertLaterCompensationFor(UUID orderId) {
        jdbc.update("insert into order_outbox_event (id, aggregate_type, aggregate_id, event_id, "
                        + "event_type, schema_version, topic, event_key, payload, status, created_at, "
                        + "attempt_count) values (?, 'Order', ?, ?, 'InventoryReleaseRequested', 1, ?, ?, "
                        + "'{\"eventId\":\"later\"}', 'PENDING', now() + interval '1 second', 0)",
                UUID.randomUUID(), orderId, UUID.randomUUID(),
                KafkaTopics.ORDER_COMPENSATION_V1, orderId.toString());
    }

    /** Claims a batch in its own transaction and rolls it back, so only the selection is observed. */
    private List<OrderOutboxEvent> claimBatch() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            List<OrderOutboxEvent> claimed = outboxEvents.lockNextBatch(20);
            status.setRollbackOnly();
            return List.copyOf(claimed);
        });
    }
}
