package com.sumedha.commerce.payment.integration;

import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.payment.dto.request.CreatePaymentRequest;
import com.sumedha.commerce.payment.dto.response.PaymentResponse;
import com.sumedha.commerce.payment.entity.Payment;
import com.sumedha.commerce.payment.enums.PaymentStatus;
import com.sumedha.commerce.payment.repository.PaymentRepository;
import com.sumedha.commerce.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class PaymentPostgresIntegrationTest {

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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        payments.deleteAll();
    }

    // ---------- Flyway / schema ----------

    @Test
    void flywayCreatesThePaymentSchema() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertTrue(tables.containsAll(List.of("payments", "flyway_schema_history")));
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
