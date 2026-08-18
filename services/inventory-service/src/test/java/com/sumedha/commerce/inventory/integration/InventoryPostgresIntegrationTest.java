package com.sumedha.commerce.inventory.integration;

import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.inventory.dto.request.CreateInventoryRequest;
import com.sumedha.commerce.inventory.dto.request.StockQuantityRequest;
import com.sumedha.commerce.inventory.dto.request.UpdateInventoryQuantityRequest;
import com.sumedha.commerce.inventory.dto.response.InventoryResponse;
import com.sumedha.commerce.inventory.entity.Inventory;
import com.sumedha.commerce.inventory.repository.InventoryRepository;
import com.sumedha.commerce.inventory.service.InventoryService;
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

import java.util.ArrayList;
import java.util.List;
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
class InventoryPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inventory_test")
            .withUsername("inventory")
            .withPassword("inventory");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private InventoryService inventoryService;
    @Autowired private InventoryRepository inventories;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        inventories.deleteAll();
    }

    // ---------- Flyway / schema ----------

    @Test
    void flywayCreatesTheInventorySchema() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertTrue(tables.containsAll(List.of("inventory", "flyway_schema_history")));
    }

    @Test
    void inventoryTableHasExpectedColumns() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'inventory'", String.class);
        assertTrue(columns.containsAll(List.of(
                "inventory_id", "product_id", "quantity", "reserved_quantity", "created_at", "updated_at")));
    }

    @Test
    void productIdHasNoForeignKeyToAnotherTable() {
        List<String> foreignKeys = jdbc.queryForList(
                "select constraint_name from information_schema.table_constraints " +
                        "where table_name = 'inventory' and constraint_type = 'FOREIGN KEY'", String.class);
        assertTrue(foreignKeys.isEmpty(), "inventory table must not reference any other table via foreign key");
    }

    // ---------- Repository ----------

    @Test
    void repositoryPersistsAndReadsInventoryWithTimestamps() {
        UUID productId = UUID.randomUUID();
        Inventory saved = inventories.saveAndFlush(new Inventory(productId, 10));

        Inventory found = inventories.findById(saved.getId()).orElseThrow();
        assertEquals(productId, found.getProductId());
        assertEquals(10, found.getQuantity());
        assertEquals(0, found.getReservedQuantity());
        assertEquals(found.getCreatedAt(), found.getUpdatedAt());
        assertTrue(found.getCreatedAt() != null);
    }

    @Test
    void findByProductIdAndExistsByProductId() {
        UUID productId = UUID.randomUUID();
        inventories.saveAndFlush(new Inventory(productId, 5));

        assertTrue(inventories.existsByProductId(productId));
        assertFalse(inventories.existsByProductId(UUID.randomUUID()));
        assertEquals(productId, inventories.findByProductId(productId).orElseThrow().getProductId());
    }

    @Test
    void availableQuantityIsDerivedFromQuantityMinusReserved() {
        UUID productId = UUID.randomUUID();
        Inventory inventory = new Inventory(productId, 10);
        inventory.update(10, 4);
        Inventory saved = inventories.saveAndFlush(inventory);

        assertEquals(6, inventories.findById(saved.getId()).orElseThrow().getAvailableQuantity());
    }

    @Test
    void productIdUniquenessIsEnforcedAtTheDatabaseLevel() {
        UUID productId = UUID.randomUUID();
        inventories.saveAndFlush(new Inventory(productId, 10));

        assertThrows(DataIntegrityViolationException.class,
                () -> inventories.saveAndFlush(new Inventory(productId, 5)));
    }

    // ---------- Database constraints ----------

    @Test
    void negativeQuantityIsRejectedByCheckConstraint() {
        Inventory inventory = new Inventory(UUID.randomUUID(), 10);
        inventory.update(-1, 0);

        assertThrows(DataIntegrityViolationException.class, () -> inventories.saveAndFlush(inventory));
    }

    @Test
    void negativeReservedQuantityIsRejectedByCheckConstraint() {
        Inventory inventory = new Inventory(UUID.randomUUID(), 10);
        inventory.update(10, -1);

        assertThrows(DataIntegrityViolationException.class, () -> inventories.saveAndFlush(inventory));
    }

    @Test
    void reservedQuantityGreaterThanQuantityIsRejectedByCheckConstraint() {
        Inventory inventory = new Inventory(UUID.randomUUID(), 10);
        inventory.update(10, 11);

        assertThrows(DataIntegrityViolationException.class, () -> inventories.saveAndFlush(inventory));
    }

    @Test
    void duplicateProductIdIsRejectedByUniqueConstraint() {
        UUID productId = UUID.randomUUID();
        inventories.saveAndFlush(new Inventory(productId, 10));

        assertThrows(DataIntegrityViolationException.class,
                () -> inventories.saveAndFlush(new Inventory(productId, 20)));
    }

    // ---------- Service (create / get / update / reserve / release) ----------

    @Test
    void serviceCreateGetUpdateReserveReleaseSequence() {
        UUID productId = UUID.randomUUID();
        InventoryResponse created = inventoryService.create(new CreateInventoryRequest(productId, 10));
        assertEquals(10, created.quantity());
        assertEquals(0, created.reservedQuantity());
        assertEquals(10, created.availableQuantity());

        InventoryResponse fetched = inventoryService.getById(created.id());
        assertEquals(created.id(), fetched.id());

        InventoryResponse reserved = inventoryService.reserve(created.id(), new StockQuantityRequest(4));
        assertEquals(10, reserved.quantity());
        assertEquals(4, reserved.reservedQuantity());
        assertEquals(6, reserved.availableQuantity());

        InventoryResponse released = inventoryService.release(created.id(), new StockQuantityRequest(2));
        assertEquals(10, released.quantity());
        assertEquals(2, released.reservedQuantity());
        assertEquals(8, released.availableQuantity());

        InventoryResponse updated = inventoryService.updateQuantity(created.id(), new UpdateInventoryQuantityRequest(20));
        assertEquals(20, updated.quantity());
        assertEquals(2, updated.reservedQuantity());
        assertEquals(18, updated.availableQuantity());
    }

    @Test
    void serviceRejectsOverReservation() {
        UUID productId = UUID.randomUUID();
        InventoryResponse created = inventoryService.create(new CreateInventoryRequest(productId, 10));

        assertThrows(ConflictException.class,
                () -> inventoryService.reserve(created.id(), new StockQuantityRequest(11)));
    }

    @Test
    void serviceRejectsOverRelease() {
        UUID productId = UUID.randomUUID();
        InventoryResponse created = inventoryService.create(new CreateInventoryRequest(productId, 10));
        inventoryService.reserve(created.id(), new StockQuantityRequest(3));

        assertThrows(ConflictException.class,
                () -> inventoryService.release(created.id(), new StockQuantityRequest(4)));
    }

    @Test
    void serviceRejectsUpdatingTotalBelowReserved() {
        UUID productId = UUID.randomUUID();
        InventoryResponse created = inventoryService.create(new CreateInventoryRequest(productId, 10));
        inventoryService.reserve(created.id(), new StockQuantityRequest(6));

        assertThrows(ConflictException.class,
                () -> inventoryService.updateQuantity(created.id(), new UpdateInventoryQuantityRequest(5)));
    }

    @Test
    void serviceCreateRejectsDuplicateProductId() {
        UUID productId = UUID.randomUUID();
        inventoryService.create(new CreateInventoryRequest(productId, 10));

        assertThrows(ConflictException.class,
                () -> inventoryService.create(new CreateInventoryRequest(productId, 5)));
    }

    @Test
    void serviceGetByIdThrowsWhenMissing() {
        assertThrows(ResourceNotFoundException.class, () -> inventoryService.getById(UUID.randomUUID()));
    }

    // ---------- Concurrency (optimistic locking) ----------

    @Test
    void concurrentReservationsOnlyOneSucceedsAndLostUpdateIsPrevented() throws Exception {
        UUID productId = UUID.randomUUID();
        InventoryResponse created = inventoryService.create(new CreateInventoryRequest(productId, 10));

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Void> reserveSix = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                inventoryService.reserve(created.id(), new StockQuantityRequest(6));
                awaitBarrier(barrier);
            });
            return null;
        };

        ConcurrentOutcome outcome = runConcurrently(reserveSix, reserveSix);

        assertEquals(1, outcome.successCount(), "exactly one concurrent reservation must succeed");
        assertEquals(1, outcome.failures().size(),
                "exactly one concurrent reservation must fail with an optimistic locking conflict");
        assertInstanceOf(ObjectOptimisticLockingFailureException.class, outcome.failures().get(0).getCause(),
                "the losing attempt must fail with an optimistic locking conflict, not a silent overwrite");

        Inventory finalState = inventories.findById(created.id()).orElseThrow();
        assertEquals(10, finalState.getQuantity());
        assertEquals(6, finalState.getReservedQuantity());
        assertEquals(4, finalState.getAvailableQuantity());
    }

    @Test
    void concurrentQuantityUpdatesDoNotSilentlyOverwriteEachOther() throws Exception {
        UUID productId = UUID.randomUUID();
        InventoryResponse created = inventoryService.create(new CreateInventoryRequest(productId, 10));

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Void> updateTo20 = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                inventoryService.updateQuantity(created.id(), new UpdateInventoryQuantityRequest(20));
                awaitBarrier(barrier);
            });
            return null;
        };
        Callable<Void> updateTo30 = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                inventoryService.updateQuantity(created.id(), new UpdateInventoryQuantityRequest(30));
                awaitBarrier(barrier);
            });
            return null;
        };

        ConcurrentOutcome outcome = runConcurrently(updateTo20, updateTo30);

        assertEquals(1, outcome.successCount(), "exactly one concurrent update must succeed");
        assertEquals(1, outcome.failures().size(),
                "exactly one concurrent update must fail with an optimistic locking conflict");
        assertInstanceOf(ObjectOptimisticLockingFailureException.class, outcome.failures().get(0).getCause(),
                "the losing attempt must fail with an optimistic locking conflict, not a silent overwrite");

        Inventory finalState = inventories.findById(created.id()).orElseThrow();
        assertTrue(finalState.getQuantity() == 20 || finalState.getQuantity() == 30,
                "final quantity must reflect exactly one winning update, not a merge of both");
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
}
