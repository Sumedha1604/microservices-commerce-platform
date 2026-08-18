package com.sumedha.commerce.cart.integration;

import com.sumedha.commerce.cart.dto.request.AddCartItemRequest;
import com.sumedha.commerce.cart.dto.request.CreateCartRequest;
import com.sumedha.commerce.cart.dto.request.UpdateCartItemQuantityRequest;
import com.sumedha.commerce.cart.dto.response.CartResponse;
import com.sumedha.commerce.cart.entity.Cart;
import com.sumedha.commerce.cart.entity.CartItem;
import com.sumedha.commerce.cart.repository.CartItemRepository;
import com.sumedha.commerce.cart.repository.CartRepository;
import com.sumedha.commerce.cart.service.CartService;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
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
import org.springframework.transaction.annotation.Transactional;
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
class CartPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cart_test")
            .withUsername("cart")
            .withPassword("cart");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private CartService cartService;
    @Autowired private CartRepository carts;
    @Autowired private CartItemRepository items;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        items.deleteAll();
        carts.deleteAll();
    }

    // ---------- Flyway / schema ----------

    @Test
    void flywayCreatesTheCartSchema() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertTrue(tables.containsAll(List.of("carts", "cart_items", "flyway_schema_history")));
    }

    @Test
    void cartsTableHasExpectedColumns() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'carts'", String.class);
        assertTrue(columns.containsAll(List.of("cart_id", "user_id", "created_at", "updated_at")));
    }

    @Test
    void cartItemsTableHasExpectedColumns() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'cart_items'", String.class);
        assertTrue(columns.containsAll(List.of(
                "cart_item_id", "cart_id", "product_id", "quantity", "created_at", "updated_at")));
    }

    @Test
    void cartItemsHasForeignKeyToCartsOnly() {
        List<String> foreignKeyTables = jdbc.queryForList(
                "select ccu.table_name from information_schema.table_constraints tc " +
                        "join information_schema.constraint_column_usage ccu on tc.constraint_name = ccu.constraint_name " +
                        "where tc.table_name = 'cart_items' and tc.constraint_type = 'FOREIGN KEY'", String.class);
        assertEquals(List.of("carts"), foreignKeyTables);
    }

    @Test
    void cartsTableHasNoForeignKeyToAnyOtherTable() {
        List<String> foreignKeys = jdbc.queryForList(
                "select constraint_name from information_schema.table_constraints " +
                        "where table_name = 'carts' and constraint_type = 'FOREIGN KEY'", String.class);
        assertTrue(foreignKeys.isEmpty(), "carts table must not reference any other table via foreign key");
    }

    @Test
    void userIdAndProductIdHaveNoForeignKeysToExternalServiceTables() {
        List<String> cartForeignKeys = jdbc.queryForList(
                "select constraint_name from information_schema.table_constraints " +
                        "where table_name = 'carts' and constraint_type = 'FOREIGN KEY'", String.class);
        assertTrue(cartForeignKeys.isEmpty(), "user_id must be a UUID reference only, no foreign key");

        List<String> itemForeignKeyTables = jdbc.queryForList(
                "select ccu.table_name from information_schema.table_constraints tc " +
                        "join information_schema.constraint_column_usage ccu on tc.constraint_name = ccu.constraint_name " +
                        "where tc.table_name = 'cart_items' and tc.constraint_type = 'FOREIGN KEY'", String.class);
        assertFalse(itemForeignKeyTables.contains("products"), "product_id must not reference a Product Service table");
    }

    // ---------- Repository: CartRepository ----------

    @Test
    void cartRepositoryPersistsAndReadsWithTimestamps() {
        UUID userId = UUID.randomUUID();
        Cart saved = carts.saveAndFlush(new Cart(userId));

        Cart found = carts.findById(saved.getId()).orElseThrow();
        assertEquals(userId, found.getUserId());
        assertEquals(found.getCreatedAt(), found.getUpdatedAt());
        assertTrue(found.getCreatedAt() != null);
    }

    @Test
    void findByUserIdAndExistsByUserId() {
        UUID userId = UUID.randomUUID();
        carts.saveAndFlush(new Cart(userId));

        assertTrue(carts.existsByUserId(userId));
        assertFalse(carts.existsByUserId(UUID.randomUUID()));
        assertEquals(userId, carts.findByUserId(userId).orElseThrow().getUserId());
    }

    @Test
    void userIdUniquenessIsEnforcedAtTheDatabaseLevel() {
        UUID userId = UUID.randomUUID();
        carts.saveAndFlush(new Cart(userId));

        assertThrows(DataIntegrityViolationException.class,
                () -> carts.saveAndFlush(new Cart(userId)));
    }

    // ---------- Repository: CartItemRepository ----------

    @Test
    void cartItemRepositoryPersistsAndReads() {
        Cart cart = carts.saveAndFlush(new Cart(UUID.randomUUID()));
        UUID productId = UUID.randomUUID();
        CartItem saved = items.saveAndFlush(new CartItem(cart.getId(), productId, 3));

        CartItem found = items.findById(saved.getId()).orElseThrow();
        assertEquals(cart.getId(), found.getCartId());
        assertEquals(productId, found.getProductId());
        assertEquals(3, found.getQuantity());
    }

    @Test
    void findByCartIdAndFindByCartIdAndProductIdAndExists() {
        Cart cart = carts.saveAndFlush(new Cart(UUID.randomUUID()));
        UUID productId = UUID.randomUUID();
        items.saveAndFlush(new CartItem(cart.getId(), productId, 2));

        assertEquals(1, items.findByCartId(cart.getId()).size());
        assertTrue(items.existsByCartIdAndProductId(cart.getId(), productId));
        assertFalse(items.existsByCartIdAndProductId(cart.getId(), UUID.randomUUID()));
        assertEquals(productId, items.findByCartIdAndProductId(cart.getId(), productId).orElseThrow().getProductId());
    }

    @Test
    @Transactional
    void deleteByCartIdRemovesAllItemsForCart() {
        Cart cart = carts.saveAndFlush(new Cart(UUID.randomUUID()));
        items.saveAndFlush(new CartItem(cart.getId(), UUID.randomUUID(), 1));
        items.saveAndFlush(new CartItem(cart.getId(), UUID.randomUUID(), 2));

        items.deleteByCartId(cart.getId());

        assertTrue(items.findByCartId(cart.getId()).isEmpty());
    }

    // ---------- Database constraints ----------

    @Test
    void duplicateCartItemForSameCartAndProductIsRejectedByUniqueConstraint() {
        Cart cart = carts.saveAndFlush(new Cart(UUID.randomUUID()));
        UUID productId = UUID.randomUUID();
        items.saveAndFlush(new CartItem(cart.getId(), productId, 1));

        assertThrows(DataIntegrityViolationException.class,
                () -> items.saveAndFlush(new CartItem(cart.getId(), productId, 2)));
    }

    @Test
    void zeroOrNegativeQuantityIsRejectedByCheckConstraint() {
        Cart cart = carts.saveAndFlush(new Cart(UUID.randomUUID()));

        assertThrows(DataIntegrityViolationException.class,
                () -> items.saveAndFlush(new CartItem(cart.getId(), UUID.randomUUID(), 0)));
    }

    @Test
    void cartItemReferencingMissingCartIsRejected() {
        assertThrows(DataIntegrityViolationException.class,
                () -> items.saveAndFlush(new CartItem(UUID.randomUUID(), UUID.randomUUID(), 1)));
    }

    // ---------- Cascade ----------

    @Test
    void deletingCartCascadesToItsItems() {
        Cart cart = carts.saveAndFlush(new Cart(UUID.randomUUID()));
        items.saveAndFlush(new CartItem(cart.getId(), UUID.randomUUID(), 1));
        items.saveAndFlush(new CartItem(cart.getId(), UUID.randomUUID(), 2));

        carts.delete(cart);
        carts.flush();

        assertTrue(items.findByCartId(cart.getId()).isEmpty());
    }

    // ---------- Service (create / get / add / update / remove / clear) ----------

    @Test
    void serviceCreateGetAddUpdateRemoveClearSequence() {
        UUID userId = UUID.randomUUID();
        CartResponse created = cartService.createCart(new CreateCartRequest(userId));
        assertEquals(userId, created.userId());
        assertTrue(created.items().isEmpty());

        CartResponse fetchedById = cartService.getCartById(created.id());
        assertEquals(created.id(), fetchedById.id());

        CartResponse fetchedByUser = cartService.getCartByUserId(userId);
        assertEquals(created.id(), fetchedByUser.id());

        UUID productId = UUID.randomUUID();
        CartResponse afterAdd = cartService.addItem(created.id(), new AddCartItemRequest(productId, 2));
        assertEquals(1, afterAdd.items().size());
        assertEquals(2, afterAdd.items().get(0).quantity());

        CartResponse afterAddAgain = cartService.addItem(created.id(), new AddCartItemRequest(productId, 3));
        assertEquals(1, afterAddAgain.items().size());
        assertEquals(5, afterAddAgain.items().get(0).quantity());

        CartResponse afterUpdate = cartService.updateItemQuantity(
                created.id(), productId, new UpdateCartItemQuantityRequest(10));
        assertEquals(10, afterUpdate.items().get(0).quantity());

        CartResponse afterRemove = cartService.removeItem(created.id(), productId);
        assertTrue(afterRemove.items().isEmpty());

        cartService.addItem(created.id(), new AddCartItemRequest(UUID.randomUUID(), 1));
        cartService.addItem(created.id(), new AddCartItemRequest(UUID.randomUUID(), 1));
        CartResponse afterClear = cartService.clearCart(created.id());
        assertTrue(afterClear.items().isEmpty());
    }

    @Test
    void serviceCreateRejectsDuplicateUserId() {
        UUID userId = UUID.randomUUID();
        cartService.createCart(new CreateCartRequest(userId));

        assertThrows(ConflictException.class, () -> cartService.createCart(new CreateCartRequest(userId)));
    }

    @Test
    void serviceGetCartByIdThrowsWhenMissing() {
        assertThrows(ResourceNotFoundException.class, () -> cartService.getCartById(UUID.randomUUID()));
    }

    @Test
    void serviceGetCartByUserIdThrowsWhenMissing() {
        assertThrows(ResourceNotFoundException.class, () -> cartService.getCartByUserId(UUID.randomUUID()));
    }

    // ---------- Concurrency (optimistic locking) ----------

    @Test
    void concurrentAddItemOnlyOneSucceedsAndLostUpdateIsPrevented() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CartResponse created = cartService.createCart(new CreateCartRequest(userId));
        cartService.addItem(created.id(), new AddCartItemRequest(productId, 1));

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Void> addTwo = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                items.findByCartIdAndProductId(created.id(), productId);
                awaitBarrier(barrier);
                cartService.addItem(created.id(), new AddCartItemRequest(productId, 2));
            });
            return null;
        };
        Callable<Void> addThree = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                items.findByCartIdAndProductId(created.id(), productId);
                awaitBarrier(barrier);
                cartService.addItem(created.id(), new AddCartItemRequest(productId, 3));
            });
            return null;
        };

        ConcurrentOutcome outcome = runConcurrently(addTwo, addThree);

        assertEquals(1, outcome.successCount(), "exactly one concurrent addItem must succeed");
        assertEquals(1, outcome.failures().size(),
                "exactly one concurrent addItem must fail with an optimistic locking conflict");
        assertInstanceOf(ObjectOptimisticLockingFailureException.class, outcome.failures().get(0).getCause(),
                "the losing attempt must fail with an optimistic locking conflict, not a silent overwrite");

        int finalQuantity = items.findByCartIdAndProductId(created.id(), productId).orElseThrow().getQuantity();
        assertTrue(finalQuantity == 3 || finalQuantity == 4,
                "final quantity must reflect exactly one winning addItem (1+2 or 1+3), not a lost update");
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
