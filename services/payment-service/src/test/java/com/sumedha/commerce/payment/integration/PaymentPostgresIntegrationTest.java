package com.sumedha.commerce.payment.integration;

import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.payment.dto.request.AuthorizePaymentRequest;
import com.sumedha.commerce.payment.dto.request.CreatePaymentRequest;
import com.sumedha.commerce.payment.dto.request.FailPaymentRequest;
import com.sumedha.commerce.payment.dto.response.PaymentResponse;
import com.sumedha.commerce.payment.entity.Payment;
import com.sumedha.commerce.payment.enums.PaymentStatus;
import com.sumedha.commerce.payment.messaging.PaymentEventPublisher;
import com.sumedha.commerce.payment.messaging.PaymentOutboxBatchProcessor;
import com.sumedha.commerce.payment.repository.PaymentOutboxEventRepository;
import com.sumedha.commerce.payment.repository.PaymentRepository;
import com.sumedha.commerce.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        // This test is about Postgres, not messaging. KafkaAdmin must not create topics...
        "spring.kafka.admin.auto-create=false",
        // ...and the bootstrap address must never be the developer's local broker, so a stray
        // connection could not reach it. Nothing actually connects: see the mocked publisher.
        "spring.kafka.bootstrap-servers=localhost:59997",
        "payment.outbox.enabled=false"
})
@Testcontainers
class PaymentPostgresIntegrationTest {

    @MockitoBean
    private PaymentEventPublisher paymentEventPublisher;

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

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentOutboxEventRepository outboxEvents;
    @Autowired private PaymentOutboxBatchProcessor outboxProcessor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        reset(paymentEventPublisher);
        outboxEvents.deleteAll();
        payments.deleteAll();
    }

    // ---------- Flyway / schema ----------

    @Test
    void flywayCreatesThePaymentSchema() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertTrue(tables.containsAll(List.of("payments", "payment_outbox_event", "flyway_schema_history")));
    }

    @Test
    void paymentsTableHasExpectedColumnsAndTypes() {
        assertColumn("payments", "payment_id", "uuid", "NO");
        assertColumn("payments", "order_id", "uuid", "NO");
        assertColumn("payments", "user_id", "uuid", "NO");
        assertVarcharColumn("payments", "status", 30, "NO");
        assertNumericColumn("payments", "amount", 19, 2, "NO");
        assertVarcharColumn("payments", "currency", 3, "NO");
        assertVarcharColumn("payments", "provider", 50, "YES");
        assertVarcharColumn("payments", "provider_reference", 255, "YES");
        assertVarcharColumn("payments", "failure_reason", 500, "YES");
        assertColumn("payments", "created_at", "timestamp with time zone", "NO");
        assertColumn("payments", "updated_at", "timestamp with time zone", "NO");
        assertColumn("payments", "version", "bigint", "NO");
    }

    @Test
    void paymentIdIsThePrimaryKeyOfPayments() {
        assertEquals(List.of("payment_id"), primaryKeyColumns("payments"));
    }

    @Test
    void orderIdHasAUniqueConstraint() {
        List<Map<String, Object>> uniqueConstraints = jdbc.queryForList(
                "select tc.constraint_name from information_schema.table_constraints tc " +
                        "join information_schema.key_column_usage kcu on tc.constraint_name = kcu.constraint_name " +
                        "where tc.table_name = 'payments' and tc.constraint_type = 'UNIQUE' and kcu.column_name = 'order_id'");
        assertEquals(1, uniqueConstraints.size(), "order_id must be covered by exactly one UNIQUE constraint");
    }

    // ---------- CRITICAL MICROSERVICE BOUNDARY ----------

    @Test
    void paymentsTableHasNoForeignKeysToOtherServices() {
        List<String> foreignKeys = jdbc.queryForList(
                "select tc.constraint_name from information_schema.table_constraints tc " +
                        "where tc.constraint_type = 'FOREIGN KEY' and tc.table_name = 'payments'", String.class);
        assertTrue(foreignKeys.isEmpty(),
                "payments table must not reference any other table via foreign key (order_id and user_id are UUID references only)");
    }

    // ---------- Repository ----------

    @Test
    void paymentRepositorySavesAndReadsAPayment() {
        Payment saved = payments.saveAndFlush(new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "USD"));

        Payment found = payments.findById(saved.getId()).orElseThrow();
        assertEquals(saved.getOrderId(), found.getOrderId());
        assertEquals(PaymentStatus.PENDING, found.getStatus());
    }

    @Test
    void findByOrderIdReturnsThePayment() {
        UUID orderId = UUID.randomUUID();
        Payment saved = payments.saveAndFlush(new Payment(orderId, UUID.randomUUID(), new BigDecimal("10.00"), "USD"));

        Payment found = payments.findByOrderId(orderId).orElseThrow();
        assertEquals(saved.getId(), found.getId());
    }

    @Test
    void existsByOrderIdReflectsPersistedState() {
        UUID orderId = UUID.randomUUID();
        assertFalse(payments.existsByOrderId(orderId));

        payments.saveAndFlush(new Payment(orderId, UUID.randomUUID(), new BigDecimal("10.00"), "USD"));

        assertTrue(payments.existsByOrderId(orderId));
    }

    @Test
    void findByUserIdOrderByCreatedAtDescReturnsNewestFirst() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        Payment first = payments.saveAndFlush(new Payment(UUID.randomUUID(), userId, BigDecimal.TEN, "USD"));
        Thread.sleep(5);
        Payment second = payments.saveAndFlush(new Payment(UUID.randomUUID(), userId, BigDecimal.TEN, "USD"));
        Thread.sleep(5);
        Payment third = payments.saveAndFlush(new Payment(UUID.randomUUID(), userId, BigDecimal.TEN, "USD"));

        List<Payment> found = payments.findByUserIdOrderByCreatedAtDesc(userId);

        assertEquals(List.of(third.getId(), second.getId(), first.getId()),
                found.stream().map(Payment::getId).toList());
    }

    // ---------- Database constraints ----------

    @Test
    void duplicateOrderIdIsRejectedByUniqueConstraint() {
        UUID orderId = UUID.randomUUID();
        payments.saveAndFlush(new Payment(orderId, UUID.randomUUID(), new BigDecimal("10.00"), "USD"));

        Payment duplicate = new Payment(orderId, UUID.randomUUID(), new BigDecimal("20.00"), "USD");
        assertThrows(DataIntegrityViolationException.class, () -> payments.saveAndFlush(duplicate));
    }

    @Test
    void negativeAmountIsRejectedByCheckConstraint() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("-1.00"), "USD");
        assertThrows(DataIntegrityViolationException.class, () -> payments.saveAndFlush(payment));
    }

    // ---------- Service integration: create ----------

    @Test
    void createPersistsPendingPaymentWithUppercaseCurrency() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(orderId, userId, new BigDecimal("25.50"), "usd"));

        assertEquals(PaymentStatus.PENDING, response.status());
        assertEquals("USD", response.currency());

        Payment persisted = payments.findById(response.id()).orElseThrow();
        assertEquals(PaymentStatus.PENDING, persisted.getStatus());
        assertEquals("USD", persisted.getCurrency());
        assertEquals(0, new BigDecimal("25.50").compareTo(persisted.getAmount()));
    }

    @Test
    void createRejectsDuplicateOrder() {
        UUID orderId = UUID.randomUUID();
        paymentService.create(new CreatePaymentRequest(orderId, UUID.randomUUID(), new BigDecimal("10.00"), "USD"));

        assertThrows(ConflictException.class,
                () -> paymentService.create(new CreatePaymentRequest(orderId, UUID.randomUUID(), new BigDecimal("10.00"), "USD")));
    }

    @Test
    void getByIdThrowsWhenMissing() {
        assertThrows(ResourceNotFoundException.class, () -> paymentService.getById(UUID.randomUUID()));
    }

    // ---------- Concurrency: duplicate create race ----------

    @Test
    void concurrentCreateForSameOrderExactlyOneSucceedsAndTheLoserFailsWithConflict() throws Exception {
        UUID orderId = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Void> createFirst = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                awaitBarrier(barrier);
                paymentService.create(new CreatePaymentRequest(orderId, UUID.randomUUID(), new BigDecimal("10.00"), "USD"));
            });
            return null;
        };
        Callable<Void> createSecond = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                awaitBarrier(barrier);
                paymentService.create(new CreatePaymentRequest(orderId, UUID.randomUUID(), new BigDecimal("20.00"), "USD"));
            });
            return null;
        };

        ConcurrentOutcome outcome = runConcurrently(createFirst, createSecond);

        assertEquals(1, outcome.successCount(), "exactly one concurrent create for the same order must succeed");
        assertEquals(1, outcome.failures().size(), "exactly one concurrent create for the same order must fail");
        assertInstanceOf(ConflictException.class, outcome.failures().get(0).getCause(),
                "the losing concurrent create must fail with ConflictException (409), not a generic 500");

        List<Payment> stored = payments.findAll().stream()
                .filter(payment -> payment.getOrderId().equals(orderId))
                .toList();
        assertEquals(1, stored.size(), "exactly one payment row must exist in the database for the order");
    }

    // ---------- Service integration: transitions ----------

    private UUID createPending() {
        return paymentService.create(new CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "USD")).id();
    }

    @Test
    void pendingTransitionsToAuthorized() {
        UUID id = createPending();
        PaymentResponse response = paymentService.authorize(id, authorizeRequest());
        assertEquals(PaymentStatus.AUTHORIZED, response.status());
    }

    @Test
    void authorizedTransitionsToCaptured() {
        UUID id = createPending();
        paymentService.authorize(id, authorizeRequest());
        PaymentResponse response = paymentService.capture(id);
        assertEquals(PaymentStatus.CAPTURED, response.status());
    }

    @Test
    void pendingTransitionsToFailed() {
        UUID id = createPending();
        PaymentResponse response = paymentService.fail(id, failRequest());
        assertEquals(PaymentStatus.FAILED, response.status());
    }

    @Test
    void authorizedTransitionsToFailed() {
        UUID id = createPending();
        paymentService.authorize(id, authorizeRequest());
        PaymentResponse response = paymentService.fail(id, failRequest());
        assertEquals(PaymentStatus.FAILED, response.status());
    }

    @Test
    void pendingTransitionsToCancelled() {
        UUID id = createPending();
        PaymentResponse response = paymentService.cancel(id);
        assertEquals(PaymentStatus.CANCELLED, response.status());
    }

    @Test
    void authorizedTransitionsToCancelled() {
        UUID id = createPending();
        paymentService.authorize(id, authorizeRequest());
        PaymentResponse response = paymentService.cancel(id);
        assertEquals(PaymentStatus.CANCELLED, response.status());
    }

    @Test
    void capturedTransitionsToRefunded() {
        UUID id = createPending();
        paymentService.authorize(id, authorizeRequest());
        paymentService.capture(id);
        PaymentResponse response = paymentService.refund(id);
        assertEquals(PaymentStatus.REFUNDED, response.status());
    }

    @Test
    void capturingAPendingPaymentIsRejected() {
        UUID id = createPending();
        assertThrows(ConflictException.class, () -> paymentService.capture(id));
    }

    @Test
    void authorizingAnAlreadyAuthorizedPaymentIsRejected() {
        UUID id = createPending();
        paymentService.authorize(id, authorizeRequest());
        assertThrows(ConflictException.class, () -> paymentService.authorize(id, authorizeRequest()));
    }

    @Test
    void refundingAPendingPaymentIsRejected() {
        UUID id = createPending();
        assertThrows(ConflictException.class, () -> paymentService.refund(id));
    }

    @Test
    void cancellingACapturedPaymentIsRejected() {
        UUID id = createPending();
        paymentService.authorize(id, authorizeRequest());
        paymentService.capture(id);
        assertThrows(ConflictException.class, () -> paymentService.cancel(id));
    }

    @Test
    void failingARefundedPaymentIsRejected() {
        UUID id = createPending();
        paymentService.authorize(id, authorizeRequest());
        paymentService.capture(id);
        paymentService.refund(id);
        assertThrows(ConflictException.class, () -> paymentService.fail(id, failRequest()));
    }

    @Test
    void authorizePersistsProviderAndProviderReference() {
        UUID id = createPending();
        PaymentResponse response = paymentService.authorize(id, authorizeRequest());

        assertEquals("stripe", response.provider());
        assertEquals("ref-123", response.providerReference());

        Payment persisted = payments.findById(id).orElseThrow();
        assertEquals("stripe", persisted.getProvider());
        assertEquals("ref-123", persisted.getProviderReference());
    }

    @Test
    void failPersistsFailureReason() {
        UUID id = createPending();
        PaymentResponse response = paymentService.fail(id, failRequest());

        assertEquals("card declined", response.failureReason());

        Payment persisted = payments.findById(id).orElseThrow();
        assertEquals("card declined", persisted.getFailureReason());
    }

    // ---------- Transactional outbox ----------

    @Test
    void authorizeAtomicallyPersistsTheExactAuthorizedEnvelope() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID id = paymentService.create(new CreatePaymentRequest(
                orderId, userId, new BigDecimal("59.97"), "USD")).id();

        paymentService.authorize(id, authorizeRequest());

        Map<String, Object> row = singleOutboxRow();
        assertEquals("Payment", row.get("aggregate_type"));
        assertEquals(id, row.get("aggregate_id"));
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, row.get("event_type"));
        assertEquals(orderId.toString(), row.get("event_key"));
        assertEquals("PENDING", row.get("status"));
        assertNotNull(row.get("event_id"));
        EventEnvelope<PaymentAuthorizedEvent> envelope = json().readValue((String) row.get("payload"),
                new TypeReference<EventEnvelope<PaymentAuthorizedEvent>>() {});
        assertEquals(row.get("event_id"), envelope.eventId());
        assertEquals(EventTypes.PAYMENT_AUTHORIZED, envelope.eventType());
        assertEquals(1, envelope.schemaVersion());
        assertEquals(id, envelope.payload().paymentId());
        assertEquals(orderId, envelope.payload().orderId());
        assertEquals(userId, envelope.payload().userId());
        assertEquals(0, new BigDecimal("59.97").compareTo(envelope.payload().amount()));
    }

    @Test
    void failAtomicallyPersistsOneFailedEnvelopeWithSanitizedReason() {
        UUID id = createPending();
        paymentService.fail(id, new FailPaymentRequest("  card declined  "));

        Map<String, Object> row = singleOutboxRow();
        EventEnvelope<PaymentFailedEvent> envelope = json().readValue((String) row.get("payload"),
                new TypeReference<EventEnvelope<PaymentFailedEvent>>() {});
        assertEquals(EventTypes.PAYMENT_FAILED, envelope.eventType());
        assertEquals("card declined", envelope.payload().failureReason());
    }

    @Test
    void illegalTransitionCreatesNoOutboxRow() {
        UUID id = createPending();
        paymentService.authorize(id, authorizeRequest());
        outboxEvents.deleteAll();

        assertThrows(ConflictException.class, () -> paymentService.authorize(id, authorizeRequest()));

        assertEquals(0, outboxEvents.count());
    }

    @Test
    void outerTransactionRollbackRollsBackPaymentAndOutboxTogether() {
        UUID id = createPending();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            paymentService.authorize(id, authorizeRequest());
            status.setRollbackOnly();
        });

        assertEquals(PaymentStatus.PENDING, payments.findById(id).orElseThrow().getStatus());
        assertEquals(0, outboxEvents.count());
    }

    @Test
    void outboxInsertFailureRollsBackPaymentTransition() {
        UUID id = createPending();
        jdbc.execute("alter table payment_outbox_event add constraint reject_authorized_test "
                + "check (event_type <> 'PaymentAuthorized')");
        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> paymentService.authorize(id, authorizeRequest()));
        } finally {
            jdbc.execute("alter table payment_outbox_event drop constraint reject_authorized_test");
        }

        assertEquals(PaymentStatus.PENDING, payments.findById(id).orElseThrow().getStatus());
        assertEquals(0, outboxEvents.count());
    }

    @Test
    void successfulPublishMarksAcknowledgedRowPublished() {
        createAuthorizedOutbox();
        SendResult<String, String> success = successfulSendResult();
        when(paymentEventPublisher.publish(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(success));

        assertEquals(1, outboxProcessor.publishNextBatch());

        Map<String, Object> row = singleOutboxRow();
        assertEquals("PUBLISHED", row.get("status"));
        assertNotNull(row.get("published_at"));
        assertEquals(0, ((Number) row.get("attempt_count")).intValue());
    }

    @Test
    void failedPublishRemainsPendingWithDurableBackoffThenRecoversUsingSameEventId() {
        createAuthorizedOutbox();
        UUID eventId = (UUID) singleOutboxRow().get("event_id");
        when(paymentEventPublisher.publish(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        outboxProcessor.publishNextBatch();

        Map<String, Object> failed = singleOutboxRow();
        assertEquals("PENDING", failed.get("status"));
        assertEquals(1, ((Number) failed.get("attempt_count")).intValue());
        assertTrue(((String) failed.get("last_error")).contains("broker unavailable"));
        assertNotNull(failed.get("next_attempt_at"));

        jdbc.update("update payment_outbox_event set next_attempt_at = now() where event_id = ?", eventId);
        SendResult<String, String> success = successfulSendResult();
        when(paymentEventPublisher.publish(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(success));
        outboxProcessor.publishNextBatch();

        Map<String, Object> recovered = singleOutboxRow();
        assertEquals(eventId, recovered.get("event_id"));
        assertEquals("PUBLISHED", recovered.get("status"));
        assertEquals(1, ((Number) recovered.get("attempt_count")).intValue());
        assertNull(recovered.get("last_error"));
    }

    @Test
    void publishedRowsAreNotRepublishedAndBatchLimitIsRespected() {
        for (int i = 0; i < 21; i++) createAuthorizedOutbox();
        SendResult<String, String> success = successfulSendResult();
        when(paymentEventPublisher.publish(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(success));

        assertEquals(20, outboxProcessor.publishNextBatch());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from payment_outbox_event where status = 'PENDING'", Integer.class));
        verify(paymentEventPublisher, times(20)).publish(anyString(), anyString(), anyString());
    }

    @Test
    void twoConcurrentWorkersCannotClaimTheSameRow() throws Exception {
        createAuthorizedOutbox();
        CompletableFuture<SendResult<String, String>> acknowledgement = new CompletableFuture<>();
        CountDownLatch sendStarted = new CountDownLatch(1);
        when(paymentEventPublisher.publish(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            sendStarted.countDown();
            return acknowledgement;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(outboxProcessor::publishNextBatch);
            assertTrue(sendStarted.await(5, TimeUnit.SECONDS));
            Future<Integer> second = executor.submit(outboxProcessor::publishNextBatch);
            assertEquals(0, second.get(5, TimeUnit.SECONDS));
            acknowledgement.complete(successfulSendResult());
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        verify(paymentEventPublisher, times(1)).publish(anyString(), anyString(), anyString());
    }

    @Test
    void crashAfterKafkaAcknowledgementRepublishesIdenticalEventIdAndPayload() {
        createAuthorizedOutbox();
        SendResult<String, String> success = successfulSendResult();
        when(paymentEventPublisher.publish(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(success));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outboxProcessor.publishNextBatch();
            status.setRollbackOnly();
        });
        assertEquals("PENDING", singleOutboxRow().get("status"));
        outboxProcessor.publishNextBatch();

        org.mockito.ArgumentCaptor<String> payloads = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(paymentEventPublisher, times(2)).publish(anyString(), anyString(), payloads.capture());
        assertEquals(payloads.getAllValues().get(0), payloads.getAllValues().get(1));
        EventEnvelope<?> first = json().readValue(payloads.getAllValues().get(0), EventEnvelope.class);
        EventEnvelope<?> second = json().readValue(payloads.getAllValues().get(1), EventEnvelope.class);
        assertEquals(first.eventId(), second.eventId());
    }

    // ---------- Per-aggregate publication ordering ----------

    @Test
    void earlierPendingRowInBackoffBlocksTheLaterEventForTheSameAggregate() {
        UUID paymentId = createAuthorizedThenFailedOutbox();
        backOffOff(paymentId, EventTypes.PAYMENT_AUTHORIZED);

        assertEquals(0, outboxProcessor.publishNextBatch());

        verify(paymentEventPublisher, never()).publish(anyString(), anyString(), anyString());
        assertEquals(2, pendingCount(), "neither row may publish while the earlier one waits");
    }

    @Test
    void laterEventBecomesClaimableOnceTheEarlierRowIsPublished() {
        UUID paymentId = createAuthorizedThenFailedOutbox();
        backOffOff(paymentId, EventTypes.PAYMENT_AUTHORIZED);
        assertEquals(0, outboxProcessor.publishNextBatch());

        jdbc.update("update payment_outbox_event set status = 'PUBLISHED', published_at = now(), "
                + "next_attempt_at = null where aggregate_id = ? and event_type = ?",
                paymentId, EventTypes.PAYMENT_AUTHORIZED);
        stubSuccessfulSend();

        assertEquals(1, outboxProcessor.publishNextBatch());

        assertEquals("PUBLISHED", statusOf(paymentId, EventTypes.PAYMENT_FAILED));
        assertEquals(0, pendingCount());
    }

    @Test
    void anAggregateInBackoffDoesNotBlockADifferentOrder() {
        UUID blocked = createAuthorizedThenFailedOutbox();
        backOffOff(blocked, EventTypes.PAYMENT_AUTHORIZED);
        UUID independent = createPending();
        paymentService.authorize(independent, authorizeRequest());
        stubSuccessfulSend();

        assertEquals(1, outboxProcessor.publishNextBatch());

        assertEquals("PUBLISHED", statusOf(independent, EventTypes.PAYMENT_AUTHORIZED),
                "an unrelated order must publish while another aggregate is held back");
        assertEquals("PENDING", statusOf(blocked, EventTypes.PAYMENT_AUTHORIZED));
        assertEquals("PENDING", statusOf(blocked, EventTypes.PAYMENT_FAILED));
    }

    // ---------- Interrupt ----------

    @Test
    void interruptStopsTheBatchAndLeavesTheRemainingClaimedRowsUntouched() {
        for (int i = 0; i < 3; i++) createAuthorizedOutbox();
        when(paymentEventPublisher.publish(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            // CompletableFuture.get(timeout) checks the flag first, so this send fails as interrupted.
            Thread.currentThread().interrupt();
            return new CompletableFuture<SendResult<String, String>>();
        });

        int attempted;
        boolean interruptRestored;
        try {
            attempted = outboxProcessor.publishNextBatch();
        } finally {
            // Reads and clears, so the flag cannot leak into the next test on this thread.
            interruptRestored = Thread.interrupted();
        }

        assertEquals(1, attempted, "the batch must stop at the interrupted row");
        assertTrue(interruptRestored, "the interrupt flag must be restored for the caller");
        verify(paymentEventPublisher, times(1)).publish(anyString(), anyString(), anyString());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select status, attempt_count, last_error, next_attempt_at from payment_outbox_event "
                        + "order by attempt_count desc");
        assertEquals(3, rows.size());
        assertTrue(rows.stream().allMatch(row -> "PENDING".equals(row.get("status"))),
                "every row must stay PENDING and retryable");

        Map<String, Object> attemptedRow = rows.get(0);
        assertEquals(1, ((Number) attemptedRow.get("attempt_count")).intValue());
        assertNotNull(attemptedRow.get("last_error"));
        assertNotNull(attemptedRow.get("next_attempt_at"));

        for (Map<String, Object> untouched : rows.subList(1, 3)) {
            assertEquals(0, ((Number) untouched.get("attempt_count")).intValue(),
                    "an unattempted row must not burn an attempt");
            assertNull(untouched.get("last_error"));
            assertNull(untouched.get("next_attempt_at"), "an unattempted row must not be pushed into backoff");
        }
    }

    // ---------- Money regression ----------

    @Test
    void amountWithMoreThanTwoDecimalPlacesIsRoundedHalfUpAndPersistedWithScaleTwo() {
        UUID orderId = UUID.randomUUID();
        PaymentResponse response = paymentService.create(
                new CreatePaymentRequest(orderId, UUID.randomUUID(), new BigDecimal("10.005"), "USD"));

        assertEquals(0, new BigDecimal("10.01").compareTo(response.amount()));

        BigDecimal rawAmount = jdbc.queryForObject("select amount from payments where payment_id = ?", BigDecimal.class, response.id());
        assertEquals(2, rawAmount.scale(), "amount must be stored with scale 2, matching NUMERIC(19,2)");
        assertEquals(0, new BigDecimal("10.01").compareTo(rawAmount));
        assertEquals(0, response.amount().compareTo(rawAmount), "returned amount must equal persisted amount");
    }

    // ---------- Optimistic locking ----------

    @Test
    void paymentResponseDoesNotExposeVersion() {
        for (RecordComponent component : PaymentResponse.class.getRecordComponents()) {
            assertFalse(component.getName().equalsIgnoreCase("version"),
                    "PaymentResponse must not expose the optimistic locking version field");
        }
    }

    @Test
    void concurrentAuthorizeAndCancelOnTheSamePendingPaymentOnlyOneCommitsAndNoLostUpdateOccurs() throws Exception {
        UUID id = createPending();

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Void> authorize = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                paymentService.authorize(id, authorizeRequest());
                awaitBarrier(barrier);
            });
            return null;
        };
        Callable<Void> cancel = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                paymentService.cancel(id);
                awaitBarrier(barrier);
            });
            return null;
        };

        ConcurrentOutcome outcome = runConcurrently(authorize, cancel);

        assertEquals(1, outcome.successCount(), "exactly one concurrent transition must commit");
        assertEquals(1, outcome.failures().size(),
                "exactly one concurrent transition must fail with an optimistic locking conflict");
        assertInstanceOf(ObjectOptimisticLockingFailureException.class, outcome.failures().get(0).getCause(),
                "the losing attempt must fail with an optimistic locking conflict, not a silent lost update");

        Payment finalState = payments.findById(id).orElseThrow();
        assertTrue(finalState.getStatus() == PaymentStatus.AUTHORIZED || finalState.getStatus() == PaymentStatus.CANCELLED,
                "final status must be whichever valid transition won, with no silent lost update");
    }

    private static ConcurrentOutcome runConcurrently(Callable<Void> first, Callable<Void> second) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = List.of(executor.submit(first), executor.submit(second));

            int successCount = 0;
            List<ExecutionException> failures = new ArrayList<>();
            for (Future<Void> future : futures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    successCount++;
                } catch (ExecutionException e) {
                    failures.add(e);
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new IllegalStateException("concurrent attempt did not complete in time", e);
                }
            }
            return new ConcurrentOutcome(successCount, failures);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("failed waiting for concurrent attempt to reach the barrier", e);
        }
    }

    private record ConcurrentOutcome(int successCount, List<ExecutionException> failures) {}

    /** Two ordered outbox rows for one aggregate: PENDING -> AUTHORIZED -> FAILED is a legal path. */
    private UUID createAuthorizedThenFailedOutbox() {
        UUID id = createPending();
        paymentService.authorize(id, authorizeRequest());
        paymentService.fail(id, new FailPaymentRequest("chargeback"));
        return id;
    }

    /** Pushes one row far enough into the future that it cannot be claimed by this test. */
    private void backOffOff(UUID aggregateId, String eventType) {
        jdbc.update("update payment_outbox_event set attempt_count = 1, last_error = 'forced', "
                + "next_attempt_at = now() + interval '5 minutes' where aggregate_id = ? and event_type = ?",
                aggregateId, eventType);
    }

    private String statusOf(UUID aggregateId, String eventType) {
        return jdbc.queryForObject("select status from payment_outbox_event "
                + "where aggregate_id = ? and event_type = ?", String.class, aggregateId, eventType);
    }

    private int pendingCount() {
        return jdbc.queryForObject(
                "select count(*) from payment_outbox_event where status = 'PENDING'", Integer.class);
    }

    private void stubSuccessfulSend() {
        // Built before when(...) so the nested mock() calls are not read as unfinished stubbing.
        SendResult<String, String> success = successfulSendResult();
        when(paymentEventPublisher.publish(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(success));
    }

    private void createAuthorizedOutbox() {
        UUID id = createPending();
        paymentService.authorize(id, authorizeRequest());
    }

    private Map<String, Object> singleOutboxRow() {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from payment_outbox_event");
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    private static ObjectMapper json() {
        return JsonMapper.builder().build();
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, String> successfulSendResult() {
        SendResult<String, String> result = mock(SendResult.class);
        org.apache.kafka.clients.producer.RecordMetadata metadata =
                mock(org.apache.kafka.clients.producer.RecordMetadata.class);
        when(metadata.topic()).thenReturn("payment.events.v1");
        when(metadata.partition()).thenReturn(0);
        when(metadata.offset()).thenReturn(1L);
        when(result.getRecordMetadata()).thenReturn(metadata);
        return result;
    }

    private com.sumedha.commerce.payment.dto.request.AuthorizePaymentRequest authorizeRequest() {
        return new com.sumedha.commerce.payment.dto.request.AuthorizePaymentRequest("stripe", "ref-123");
    }

    private com.sumedha.commerce.payment.dto.request.FailPaymentRequest failRequest() {
        return new com.sumedha.commerce.payment.dto.request.FailPaymentRequest("card declined");
    }

    private void assertColumn(String table, String column, String expectedType, String expectedNullable) {
        Map<String, Object> row = columnRow(table, column);
        assertEquals(expectedType, row.get("data_type"));
        assertEquals(expectedNullable, row.get("is_nullable"));
    }

    private void assertVarcharColumn(String table, String column, int expectedLength, String expectedNullable) {
        Map<String, Object> row = columnRow(table, column);
        assertEquals("character varying", row.get("data_type"));
        assertEquals(expectedLength, ((Number) row.get("character_maximum_length")).intValue());
        assertEquals(expectedNullable, row.get("is_nullable"));
    }

    private void assertNumericColumn(String table, String column, int precision, int scale, String expectedNullable) {
        Map<String, Object> row = columnRow(table, column);
        assertEquals("numeric", row.get("data_type"));
        assertEquals(precision, ((Number) row.get("numeric_precision")).intValue());
        assertEquals(scale, ((Number) row.get("numeric_scale")).intValue());
        assertEquals(expectedNullable, row.get("is_nullable"));
    }

    private Map<String, Object> columnRow(String table, String column) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable " +
                        "from information_schema.columns where table_name = ? and column_name = ?", table, column);
        assertEquals(1, rows.size(), () -> table + "." + column + " must exist");
        return rows.get(0);
    }

    private List<String> primaryKeyColumns(String table) {
        return jdbc.queryForList(
                "select kcu.column_name from information_schema.table_constraints tc " +
                        "join information_schema.key_column_usage kcu on tc.constraint_name = kcu.constraint_name " +
                        "where tc.table_name = ? and tc.constraint_type = 'PRIMARY KEY'", String.class, table);
    }
}
