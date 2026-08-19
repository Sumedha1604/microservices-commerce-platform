package com.sumedha.commerce.order.integration;

import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.order.dto.request.CreateOrderItemRequest;
import com.sumedha.commerce.order.dto.request.CreateOrderRequest;
import com.sumedha.commerce.order.dto.response.OrderResponse;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.entity.OrderItem;
import com.sumedha.commerce.order.enums.OrderStatus;
import com.sumedha.commerce.order.repository.OrderItemRepository;
import com.sumedha.commerce.order.repository.OrderRepository;
import com.sumedha.commerce.order.service.OrderService;
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

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
class OrderPostgresIntegrationTest {

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

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository items;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        items.deleteAll();
        orders.deleteAll();
    }

    // ---------- Flyway / schema ----------

    @Test
    void flywayCreatesTheOrderSchema() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertTrue(tables.containsAll(List.of("orders", "order_items", "flyway_schema_history")));
    }

    @Test
    void ordersTableHasExpectedColumnsAndTypes() {
        assertColumn("orders", "order_id", "uuid", "NO");
        assertColumn("orders", "user_id", "uuid", "NO");
        assertVarcharColumn("orders", "status", 30, "NO");
        assertNumericColumn("orders", "subtotal", 19, 2, "NO");
        assertNumericColumn("orders", "total", 19, 2, "NO");
        assertVarcharColumn("orders", "currency", 3, "NO");
        assertColumn("orders", "created_at", "timestamp with time zone", "NO");
        assertColumn("orders", "updated_at", "timestamp with time zone", "NO");
        assertColumn("orders", "version", "bigint", "NO");
    }

    @Test
    void orderIdIsThePrimaryKeyOfOrders() {
        assertEquals(List.of("order_id"), primaryKeyColumns("orders"));
    }

    @Test
    void orderItemsTableHasExpectedColumnsAndTypes() {
        assertColumn("order_items", "order_item_id", "uuid", "NO");
        assertColumn("order_items", "order_id", "uuid", "NO");
        assertColumn("order_items", "product_id", "uuid", "NO");
        assertVarcharColumn("order_items", "product_name", 255, "NO");
        assertVarcharColumn("order_items", "sku", 100, "YES");
        assertNumericColumn("order_items", "unit_price", 19, 2, "NO");
        assertColumn("order_items", "quantity", "integer", "NO");
        assertNumericColumn("order_items", "line_total", 19, 2, "NO");
        assertColumn("order_items", "created_at", "timestamp with time zone", "NO");
    }

    @Test
    void orderItemIdIsThePrimaryKeyOfOrderItems() {
        assertEquals(List.of("order_item_id"), primaryKeyColumns("order_items"));
    }

    @Test
    void orderItemsOrderIdHasCascadingForeignKeyToOrders() {
        List<Map<String, Object>> fk = jdbc.queryForList(
                "select rc.delete_rule, ccu.table_name as referenced_table, ccu.column_name as referenced_column " +
                        "from information_schema.referential_constraints rc " +
                        "join information_schema.constraint_column_usage ccu on rc.unique_constraint_name = ccu.constraint_name " +
                        "join information_schema.table_constraints tc on rc.constraint_name = tc.constraint_name " +
                        "where tc.table_name = 'order_items'");

        assertEquals(1, fk.size(), "order_items must have exactly one foreign key");
        assertEquals("CASCADE", fk.get(0).get("delete_rule"));
        assertEquals("orders", fk.get(0).get("referenced_table"));
        assertEquals("order_id", fk.get(0).get("referenced_column"));
    }

    @Test
    void userIdAndProductIdHaveNoExternalForeignKeys() {
        List<String> foreignKeys = jdbc.queryForList(
                "select tc.constraint_name from information_schema.table_constraints tc " +
                        "where tc.constraint_type = 'FOREIGN KEY' and tc.table_name = 'orders'", String.class);
        assertTrue(foreignKeys.isEmpty(), "orders table must not reference any other table via foreign key");

        List<Map<String, Object>> orderItemForeignKeys = jdbc.queryForList(
                "select kcu.column_name from information_schema.table_constraints tc " +
                        "join information_schema.key_column_usage kcu on tc.constraint_name = kcu.constraint_name " +
                        "where tc.constraint_type = 'FOREIGN KEY' and tc.table_name = 'order_items'");
        assertEquals(1, orderItemForeignKeys.size());
        assertEquals("order_id", orderItemForeignKeys.get(0).get("column_name"),
                "the only foreign key on order_items must be order_id -> orders; product_id must not be a foreign key");
    }

    // ---------- Repository ----------

    @Test
    void orderRepositorySavesAndReadsAnOrder() {
        UUID userId = UUID.randomUUID();
        Order saved = orders.saveAndFlush(new Order(userId, new BigDecimal("10.00"), new BigDecimal("10.00"), "USD"));

        Order found = orders.findById(saved.getId()).orElseThrow();
        assertEquals(userId, found.getUserId());
        assertEquals(OrderStatus.PENDING, found.getStatus());
    }

    @Test
    void findByUserIdOrderByCreatedAtDescReturnsNewestFirst() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        Order first = orders.saveAndFlush(new Order(userId, BigDecimal.TEN, BigDecimal.TEN, "USD"));
        Thread.sleep(5);
        Order second = orders.saveAndFlush(new Order(userId, BigDecimal.TEN, BigDecimal.TEN, "USD"));
        Thread.sleep(5);
        Order third = orders.saveAndFlush(new Order(userId, BigDecimal.TEN, BigDecimal.TEN, "USD"));

        List<Order> found = orders.findByUserIdOrderByCreatedAtDesc(userId);

        assertEquals(List.of(third.getId(), second.getId(), first.getId()),
                found.stream().map(Order::getId).toList());
    }

    @Test
    void orderItemRepositoryFindsByOrderId() {
        Order order = orders.saveAndFlush(new Order(UUID.randomUUID(), new BigDecimal("20.00"), new BigDecimal("20.00"), "USD"));
        items.saveAndFlush(itemFor(order.getId(), new BigDecimal("10.00"), 2, new BigDecimal("20.00")));

        List<OrderItem> found = items.findByOrderId(order.getId());

        assertEquals(1, found.size());
        assertEquals(order.getId(), found.get(0).getOrderId());
    }

    @Test
    @Transactional
    void orderItemRepositoryDeletesByOrderId() {
        Order order = orders.saveAndFlush(new Order(UUID.randomUUID(), new BigDecimal("20.00"), new BigDecimal("20.00"), "USD"));
        items.saveAndFlush(itemFor(order.getId(), new BigDecimal("10.00"), 2, new BigDecimal("20.00")));

        items.deleteByOrderId(order.getId());

        assertTrue(items.findByOrderId(order.getId()).isEmpty());
    }

    // ---------- Database constraints ----------

    @Test
    void negativeSubtotalIsRejectedByCheckConstraint() {
        Order order = new Order(UUID.randomUUID(), new BigDecimal("-1.00"), new BigDecimal("10.00"), "USD");
        assertThrows(DataIntegrityViolationException.class, () -> orders.saveAndFlush(order));
    }

    @Test
    void negativeTotalIsRejectedByCheckConstraint() {
        Order order = new Order(UUID.randomUUID(), new BigDecimal("10.00"), new BigDecimal("-1.00"), "USD");
        assertThrows(DataIntegrityViolationException.class, () -> orders.saveAndFlush(order));
    }

    @Test
    void negativeUnitPriceIsRejectedByCheckConstraint() {
        Order order = orders.saveAndFlush(new Order(UUID.randomUUID(), new BigDecimal("10.00"), new BigDecimal("10.00"), "USD"));
        OrderItem item = itemFor(order.getId(), new BigDecimal("-1.00"), 1, new BigDecimal("10.00"));
        assertThrows(DataIntegrityViolationException.class, () -> items.saveAndFlush(item));
    }

    @Test
    void zeroQuantityIsRejectedByCheckConstraint() {
        Order order = orders.saveAndFlush(new Order(UUID.randomUUID(), new BigDecimal("10.00"), new BigDecimal("10.00"), "USD"));
        OrderItem item = itemFor(order.getId(), new BigDecimal("10.00"), 0, new BigDecimal("10.00"));
        assertThrows(DataIntegrityViolationException.class, () -> items.saveAndFlush(item));
    }

    @Test
    void negativeLineTotalIsRejectedByCheckConstraint() {
        Order order = orders.saveAndFlush(new Order(UUID.randomUUID(), new BigDecimal("10.00"), new BigDecimal("10.00"), "USD"));
        OrderItem item = itemFor(order.getId(), new BigDecimal("10.00"), 1, new BigDecimal("-10.00"));
        assertThrows(DataIntegrityViolationException.class, () -> items.saveAndFlush(item));
    }

    @Test
    void orderItemWithMissingOrderIdIsRejectedByNotNullConstraint() {
        OrderItem item = itemFor(null, new BigDecimal("10.00"), 1, new BigDecimal("10.00"));
        assertThrows(DataIntegrityViolationException.class, () -> items.saveAndFlush(item));
    }

    @Test
    void orderItemWithUnknownOrderIdIsRejectedByForeignKeyConstraint() {
        OrderItem item = itemFor(UUID.randomUUID(), new BigDecimal("10.00"), 1, new BigDecimal("10.00"));
        assertThrows(DataIntegrityViolationException.class, () -> items.saveAndFlush(item));
    }

    @Test
    void deletingAnOrderCascadesToItsItems() {
        Order order = orders.saveAndFlush(new Order(UUID.randomUUID(), new BigDecimal("20.00"), new BigDecimal("20.00"), "USD"));
        items.saveAndFlush(itemFor(order.getId(), new BigDecimal("10.00"), 2, new BigDecimal("20.00")));

        orders.delete(order);
        orders.flush();

        assertTrue(items.findByOrderId(order.getId()).isEmpty());
    }

    // ---------- Service integration (create / get / confirm / cancel) ----------

    @Test
    void createPersistsPendingOrderWithSnapshotItemsAndCalculatedTotals() {
        UUID userId = UUID.randomUUID();
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID();
        CreateOrderItemRequest item1 = new CreateOrderItemRequest(productId1, "Widget", "SKU-1", new BigDecimal("10.25"), 2);
        CreateOrderItemRequest item2 = new CreateOrderItemRequest(productId2, "Gadget", "SKU-2", new BigDecimal("5.75"), 1);

        OrderResponse response = orderService.create(new CreateOrderRequest(userId, "usd", List.of(item1, item2)));

        assertEquals(OrderStatus.PENDING, response.status());
        assertEquals("USD", response.currency());
        assertEquals(new BigDecimal("26.25"), response.subtotal());
        assertEquals(new BigDecimal("26.25"), response.total());
        assertEquals(2, response.items().size());

        Order persisted = orders.findById(response.id()).orElseThrow();
        assertEquals(OrderStatus.PENDING, persisted.getStatus());
        assertEquals("USD", persisted.getCurrency());
        assertEquals(new BigDecimal("26.25"), persisted.getSubtotal());
        assertEquals(new BigDecimal("26.25"), persisted.getTotal());

        List<OrderItem> persistedItems = items.findByOrderId(response.id());
        assertEquals(2, persistedItems.size());
        OrderItem widget = persistedItems.stream().filter(i -> i.getProductId().equals(productId1)).findFirst().orElseThrow();
        assertEquals("Widget", widget.getProductName());
        assertEquals("SKU-1", widget.getSku());
        assertEquals(new BigDecimal("10.25"), widget.getUnitPrice());
        assertEquals(2, widget.getQuantity());
        assertEquals(new BigDecimal("20.50"), widget.getLineTotal());
    }

    @Test
    void getByIdReturnsPersistedOrder() {
        OrderResponse created = orderService.create(createRequest());
        OrderResponse fetched = orderService.getById(created.id());
        assertEquals(created.id(), fetched.id());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        assertThrows(ResourceNotFoundException.class, () -> orderService.getById(UUID.randomUUID()));
    }

    @Test
    void getByUserIdReturnsOrdersNewestFirst() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        OrderResponse first = orderService.create(createRequestFor(userId));
        Thread.sleep(5);
        OrderResponse second = orderService.create(createRequestFor(userId));

        List<OrderResponse> found = orderService.getByUserId(userId);

        assertEquals(List.of(second.id(), first.id()), found.stream().map(OrderResponse::id).toList());
    }

    @Test
    void confirmTransitionsPendingToConfirmed() {
        OrderResponse created = orderService.create(createRequest());
        OrderResponse confirmed = orderService.confirm(created.id());
        assertEquals(OrderStatus.CONFIRMED, confirmed.status());
    }

    @Test
    void cancelTransitionsPendingToCancelled() {
        OrderResponse created = orderService.create(createRequest());
        OrderResponse cancelled = orderService.cancel(created.id());
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
    }

    @Test
    void cancelTransitionsConfirmedToCancelled() {
        OrderResponse created = orderService.create(createRequest());
        orderService.confirm(created.id());
        OrderResponse cancelled = orderService.cancel(created.id());
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
    }

    @Test
    void confirmingAnAlreadyConfirmedOrderIsRejected() {
        OrderResponse created = orderService.create(createRequest());
        orderService.confirm(created.id());
        assertThrows(ConflictException.class, () -> orderService.confirm(created.id()));
    }

    @Test
    void confirmingACancelledOrderIsRejected() {
        OrderResponse created = orderService.create(createRequest());
        orderService.cancel(created.id());
        assertThrows(ConflictException.class, () -> orderService.confirm(created.id()));
    }

    @Test
    void cancellingAnAlreadyCancelledOrderIsRejected() {
        OrderResponse created = orderService.create(createRequest());
        orderService.cancel(created.id());
        assertThrows(ConflictException.class, () -> orderService.cancel(created.id()));
    }

    // ---------- Money / precision ----------

    @Test
    void moneyValuesArePersistedWithNumericScaleTwoAndNoFloatingPointDrift() {
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID();
        CreateOrderItemRequest item1 = new CreateOrderItemRequest(productId1, "Widget", "SKU-1", new BigDecimal("10.25"), 2);
        CreateOrderItemRequest item2 = new CreateOrderItemRequest(productId2, "Gadget", "SKU-2", new BigDecimal("5.75"), 1);

        OrderResponse response = orderService.create(createRequestWith(item1, item2));

        assertEquals(new BigDecimal("20.50"), response.items().stream()
                .filter(i -> i.productId().equals(productId1)).findFirst().orElseThrow().lineTotal());
        assertEquals(new BigDecimal("5.75"), response.items().stream()
                .filter(i -> i.productId().equals(productId2)).findFirst().orElseThrow().lineTotal());
        assertEquals(new BigDecimal("26.25"), response.subtotal());
        assertEquals(new BigDecimal("26.25"), response.total());

        BigDecimal rawSubtotal = jdbc.queryForObject(
                "select subtotal from orders where order_id = ?", BigDecimal.class, response.id());
        assertEquals(2, rawSubtotal.scale(), "subtotal must be stored with scale 2, matching NUMERIC(19,2)");
        assertEquals(0, new BigDecimal("26.25").compareTo(rawSubtotal));
    }

    @Test
    void persistedLineTotalStaysConsistentWithNormalizedUnitPriceForMoreThanTwoDecimalPrices() {
        UUID productId1 = UUID.randomUUID();
        CreateOrderItemRequest item = new CreateOrderItemRequest(productId1, "Widget", "SKU-1", new BigDecimal("10.005"), 2);

        OrderResponse response = orderService.create(createRequestWith(item));

        assertEquals(new BigDecimal("10.01"), response.items().get(0).unitPrice());
        assertEquals(new BigDecimal("20.02"), response.items().get(0).lineTotal());
        assertEquals(new BigDecimal("20.02"), response.subtotal());
        assertEquals(new BigDecimal("20.02"), response.total());

        OrderItem persistedItem = items.findByOrderId(response.id()).get(0);
        BigDecimal rawUnitPrice = jdbc.queryForObject(
                "select unit_price from order_items where order_item_id = ?", BigDecimal.class, persistedItem.getId());
        BigDecimal rawLineTotal = jdbc.queryForObject(
                "select line_total from order_items where order_item_id = ?", BigDecimal.class, persistedItem.getId());
        BigDecimal rawSubtotal = jdbc.queryForObject(
                "select subtotal from orders where order_id = ?", BigDecimal.class, response.id());

        assertEquals(2, rawUnitPrice.scale());
        assertEquals(2, rawLineTotal.scale());
        assertEquals(0, new BigDecimal("10.01").compareTo(rawUnitPrice));
        assertEquals(0, new BigDecimal("20.02").compareTo(rawLineTotal));
        assertEquals(0, rawUnitPrice.multiply(BigDecimal.valueOf(persistedItem.getQuantity())).setScale(2, RoundingMode.HALF_UP)
                        .compareTo(rawLineTotal),
                "persisted line_total must equal persisted unit_price * quantity");
        assertEquals(0, rawLineTotal.compareTo(rawSubtotal),
                "subtotal must equal the sum of persisted line totals");
    }

    // ---------- Optimistic locking / concurrency ----------

    @Test
    void concurrentConfirmAndCancelOnTheSamePendingOrderOnlyOneCommitsAndNoLostUpdateOccurs() throws Exception {
        OrderResponse created = orderService.create(createRequest());

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Void> confirm = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                orderService.confirm(created.id());
                awaitBarrier(barrier);
            });
            return null;
        };
        Callable<Void> cancel = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                orderService.cancel(created.id());
                awaitBarrier(barrier);
            });
            return null;
        };

        ConcurrentOutcome outcome = runConcurrently(confirm, cancel);

        assertEquals(1, outcome.successCount(), "exactly one concurrent transition must commit");
        assertEquals(1, outcome.failures().size(),
                "exactly one concurrent transition must fail with an optimistic locking conflict");
        assertInstanceOf(ObjectOptimisticLockingFailureException.class, outcome.failures().get(0).getCause(),
                "the losing attempt must fail with an optimistic locking conflict, not a silent lost update");

        Order finalState = orders.findById(created.id()).orElseThrow();
        assertTrue(finalState.getStatus() == OrderStatus.CONFIRMED || finalState.getStatus() == OrderStatus.CANCELLED,
                "final status must be whichever valid transition won, with no silent lost update");
    }

    @Test
    void orderResponseDoesNotExposeVersion() {
        for (RecordComponent component : OrderResponse.class.getRecordComponents()) {
            assertFalse(component.getName().equalsIgnoreCase("version"),
                    "OrderResponse must not expose the optimistic locking version field");
        }
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

    private OrderItem itemFor(UUID orderId, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
        return new OrderItem(orderId, UUID.randomUUID(), "Widget", "SKU-1", unitPrice, quantity, lineTotal);
    }

    private CreateOrderRequest createRequest() {
        return createRequestFor(UUID.randomUUID());
    }

    private CreateOrderRequest createRequestFor(UUID userId) {
        return new CreateOrderRequest(userId, "USD",
                List.of(new CreateOrderItemRequest(UUID.randomUUID(), "Widget", "SKU-1", new BigDecimal("10.00"), 1)));
    }

    private CreateOrderRequest createRequestWith(CreateOrderItemRequest... itemRequests) {
        return new CreateOrderRequest(UUID.randomUUID(), "USD", List.of(itemRequests));
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
