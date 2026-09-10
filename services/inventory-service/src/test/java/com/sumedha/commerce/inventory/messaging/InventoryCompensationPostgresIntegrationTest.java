package com.sumedha.commerce.inventory.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.inventory.InventoryReleaseLine;
import com.sumedha.commerce.common.events.inventory.InventoryReleaseRequestedEvent;
import com.sumedha.commerce.inventory.dto.request.CreateInventoryRequest;
import com.sumedha.commerce.inventory.dto.request.StockQuantityRequest;
import com.sumedha.commerce.inventory.entity.Inventory;
import com.sumedha.commerce.inventory.repository.InventoryRepository;
import com.sumedha.commerce.inventory.repository.ProcessedEventRepository;
import com.sumedha.commerce.inventory.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compensation applied to real PostgreSQL: what a release does to stock, and - more importantly -
 * what a <em>second</em> delivery of the same release does not do.
 *
 * <p>No Kafka here: the listener is stopped and the bootstrap address is a dead port, so this
 * exercises the transactional rules directly. Broker behaviour is covered by
 * {@link InventoryCompensationKafkaIntegrationTest}.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:59997"
})
@Testcontainers
class InventoryCompensationPostgresIntegrationTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

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

    @Autowired private InventoryCompensationEventParser parser;
    @Autowired private InventoryReleaseProcessor processor;
    @Autowired private InventoryService inventoryService;
    @Autowired private InventoryRepository inventories;
    @Autowired private ProcessedEventRepository processedEvents;
    @Autowired private JdbcTemplate jdbc;

    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void clearDatabase() {
        jdbc.update("delete from processed_event");
        jdbc.update("delete from inventory");
    }

    // ---------- helpers ----------

    /** Inventory of {@code quantity} with {@code reserved} already held, as checkout would leave it. */
    private UUID stockWithReservation(int quantity, int reserved) {
        UUID productId = UUID.randomUUID();
        var created = inventoryService.create(new CreateInventoryRequest(productId, quantity));
        if (reserved > 0) {
            inventoryService.reserve(created.id(), new StockQuantityRequest(reserved));
        }
        return productId;
    }

    private String releaseEvent(UUID eventId, UUID order, List<InventoryReleaseLine> lines) {
        return JSON.writeValueAsString(new EventEnvelope<>(eventId,
                EventTypes.INVENTORY_RELEASE_REQUESTED, EventEnvelope.SCHEMA_VERSION_V1,
                Instant.now(), new InventoryReleaseRequestedEvent(order, "Payment failed: card declined", lines)));
    }

    private String releaseEvent(UUID eventId, UUID productId, int quantity) {
        return releaseEvent(eventId, orderId, List.of(new InventoryReleaseLine(productId, quantity)));
    }

    private Inventory stockOf(UUID productId) {
        return inventories.findByProductId(productId).orElseThrow();
    }

    private InventoryReleaseProcessor.Outcome apply(String event) {
        return processor.process(parser.parse(event));
    }

    // ---------- the happy path ----------

    @Test
    void releasingRestoresTheReservedQuantityAndLeavesTotalStockAlone() {
        UUID productId = stockWithReservation(10, 3);

        assertEquals(InventoryReleaseProcessor.Outcome.RELEASED,
                apply(releaseEvent(UUID.randomUUID(), productId, 3)));

        Inventory stock = stockOf(productId);
        assertEquals(0, stock.getReservedQuantity(), "the reservation is given back");
        assertEquals(10, stock.getQuantity(), "compensation releases a hold; it never changes owned stock");
        assertEquals(10, stock.getAvailableQuantity());
    }

    @Test
    void aPartialReleaseLeavesTheRestOfTheReservationHeld() {
        UUID productId = stockWithReservation(10, 5);

        apply(releaseEvent(UUID.randomUUID(), productId, 2));

        assertEquals(3, stockOf(productId).getReservedQuantity(),
                "only this order's units come back; another order's hold is untouched");
    }

    @Test
    void everyLineOfAMultiLineOrderIsReleased() {
        UUID productA = stockWithReservation(10, 3);
        UUID productB = stockWithReservation(8, 2);

        apply(releaseEvent(UUID.randomUUID(), orderId, List.of(
                new InventoryReleaseLine(productA, 3), new InventoryReleaseLine(productB, 2))));

        assertEquals(0, stockOf(productA).getReservedQuantity());
        assertEquals(0, stockOf(productB).getReservedQuantity());
    }

    @Test
    void applyingARecordWritesTheProcessedMarker() {
        UUID eventId = UUID.randomUUID();
        UUID productId = stockWithReservation(10, 3);

        apply(releaseEvent(eventId, productId, 3));

        assertTrue(processedEvents.existsById(eventId));
        assertEquals("InventoryReleaseRequested",
                processedEvents.findById(eventId).orElseThrow().getEventType());
        assertEquals(orderId, processedEvents.findById(eventId).orElseThrow().getOrderId());
    }

    // ---------- duplicate delivery ----------

    @Test
    void redeliveringTheSameEventRestoresStockOnlyOnce() {
        UUID eventId = UUID.randomUUID();
        UUID productId = stockWithReservation(10, 3);
        String event = releaseEvent(eventId, productId, 3);

        assertEquals(InventoryReleaseProcessor.Outcome.RELEASED, apply(event));
        assertEquals(InventoryReleaseProcessor.Outcome.DUPLICATE, apply(event));
        assertEquals(InventoryReleaseProcessor.Outcome.DUPLICATE, apply(event));

        assertEquals(0, stockOf(productId).getReservedQuantity(),
                "three deliveries, one release: reserved must not go negative");
        assertEquals(10, stockOf(productId).getQuantity());
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_event", Integer.class));
    }

    /**
     * The outbox republish case: a crash between the broker's acknowledgement and the PUBLISHED
     * update sends the identical bytes again. It must be absorbed, not applied.
     */
    @Test
    void aByteIdenticalRepublishIsAbsorbedAsADuplicate() {
        UUID eventId = UUID.randomUUID();
        UUID productId = stockWithReservation(10, 4);
        String event = releaseEvent(eventId, productId, 4);

        apply(event);
        int reservedAfterFirst = stockOf(productId).getReservedQuantity();
        assertEquals(InventoryReleaseProcessor.Outcome.DUPLICATE, apply(event));

        assertEquals(reservedAfterFirst, stockOf(productId).getReservedQuantity());
    }

    /**
     * A <em>different</em> eventId asking for the same release is not a duplicate and is not
     * silently absorbed - by then the stock cannot support it, and refusing is what keeps
     * reserved_quantity honest.
     */
    @Test
    void aSecondDistinctEventForAlreadyReleasedStockIsRefusedRatherThanDoubleApplied() {
        UUID productId = stockWithReservation(10, 3);
        apply(releaseEvent(UUID.randomUUID(), productId, 3));

        NonRetryableEventException refused = assertThrows(NonRetryableEventException.class,
                () -> apply(releaseEvent(UUID.randomUUID(), productId, 3)));

        assertTrue(refused.getMessage().contains("only 0 is reserved"), refused.getMessage());
        assertEquals(0, stockOf(productId).getReservedQuantity());
        assertEquals(10, stockOf(productId).getQuantity(), "owned stock is never inflated");
    }

    // ---------- concurrency ----------

    /**
     * Applies every event on its own thread, all released at the same instant, and returns what
     * each one did. A thrown exception is reported by its class name rather than rethrown, so a
     * test can assert that none occurred.
     */
    private List<String> applyConcurrently(List<String> events) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(events.size());
        ExecutorService pool = Executors.newFixedThreadPool(events.size());
        List<Callable<String>> attempts = new ArrayList<>();
        for (String event : events) {
            attempts.add(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                try {
                    return apply(event).name();
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
     * Two workers delivering the same event at the same time - a rebalance, or a redelivery racing
     * the original. Exactly one may move stock, and the others must come back as clean duplicates
     * rather than as errors the container would retry or dead-letter.
     */
    @Test
    void concurrentDeliveriesOfTheSameEventReleaseStockExactlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID productId = stockWithReservation(20, 6);
        String event = releaseEvent(eventId, productId, 6);

        List<String> outcomes = applyConcurrently(List.of(event, event, event, event));

        assertEquals(1, outcomes.stream().filter("RELEASED"::equals).count(),
                () -> "exactly one worker may release; outcomes were " + outcomes);
        assertEquals(3, outcomes.stream().filter("DUPLICATE"::equals).count(),
                () -> "the losers are acknowledged duplicates, not failures; outcomes were " + outcomes);
        assertEquals(0, stockOf(productId).getReservedQuantity(),
                "6 reserved, released once: concurrent duplicates must not restore twice");
        assertEquals(20, stockOf(productId).getQuantity());
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_event", Integer.class));
    }

    /**
     * Different orders releasing the same two products at the same time, half of them listing the
     * products in the opposite order. Every release must land: no optimistic-lock casualty and no
     * deadlock, because rows are locked FOR UPDATE in one fixed product order.
     */
    @Test
    void concurrentReleasesForDifferentOrdersOnTheSameProductsAllApply() throws Exception {
        UUID productA = stockWithReservation(100, 40);
        UUID productB = stockWithReservation(100, 40);
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            List<InventoryReleaseLine> lines = i % 2 == 0
                    ? List.of(new InventoryReleaseLine(productA, 5), new InventoryReleaseLine(productB, 5))
                    : List.of(new InventoryReleaseLine(productB, 5), new InventoryReleaseLine(productA, 5));
            events.add(releaseEvent(UUID.randomUUID(), UUID.randomUUID(), lines));
        }

        List<String> outcomes = applyConcurrently(events);

        assertEquals(8, outcomes.stream().filter("RELEASED"::equals).count(),
                () -> "every distinct release must apply; outcomes were " + outcomes);
        assertEquals(0, stockOf(productA).getReservedQuantity(), "8 x 5 released from 40 reserved");
        assertEquals(0, stockOf(productB).getReservedQuantity());
        assertEquals(100, stockOf(productA).getQuantity());
        assertEquals(8, jdbc.queryForObject("select count(*) from processed_event", Integer.class));
    }

    @Test
    void twoLinesForTheSameProductReleaseTheirSum() {
        UUID productId = stockWithReservation(10, 5);

        apply(releaseEvent(UUID.randomUUID(), orderId, List.of(
                new InventoryReleaseLine(productId, 2), new InventoryReleaseLine(productId, 3))));

        assertEquals(0, stockOf(productId).getReservedQuantity());
    }

    // ---------- rejected records ----------

    @Test
    void anUnknownProductIsNonRetryableAndChangesNothing() {
        UUID eventId = UUID.randomUUID();

        NonRetryableEventException rejected = assertThrows(NonRetryableEventException.class,
                () -> apply(releaseEvent(eventId, UUID.randomUUID(), 2)));

        assertTrue(rejected.getMessage().contains("no inventory record"), rejected.getMessage());
        assertFalse(processedEvents.existsById(eventId),
                "a record that was not applied must not leave a processed marker behind");
    }

    @Test
    void releasingMoreThanIsReservedIsRefusedRatherThanDrivingStockNegative() {
        UUID eventId = UUID.randomUUID();
        UUID productId = stockWithReservation(10, 2);

        NonRetryableEventException refused = assertThrows(NonRetryableEventException.class,
                () -> apply(releaseEvent(eventId, productId, 5)));

        assertTrue(refused.getMessage().contains("refusing"), refused.getMessage());
        assertEquals(2, stockOf(productId).getReservedQuantity(), "the reservation is untouched");
        assertFalse(processedEvents.existsById(eventId),
                "the claimed marker rolls back with the refused release, so an operator replay is not swallowed");
    }

    /**
     * All-or-nothing across lines: a good line earlier in the payload must not survive a bad line
     * later in it, or stock ends up half-restored with no record of which half.
     */
    @Test
    void oneBadLineRollsBackTheLinesThatAlreadySucceeded() {
        UUID eventId = UUID.randomUUID();
        UUID good = stockWithReservation(10, 3);
        UUID unknown = UUID.randomUUID();

        assertThrows(NonRetryableEventException.class, () -> apply(releaseEvent(eventId, orderId, List.of(
                new InventoryReleaseLine(good, 3), new InventoryReleaseLine(unknown, 1)))));

        assertEquals(3, stockOf(good).getReservedQuantity(),
                "the first line's release must roll back with the failing one");
        assertFalse(processedEvents.existsById(eventId));
    }

    @Test
    void malformedAndUnsupportedRecordsAreRejectedBeforeAnyStockIsTouched() {
        UUID productId = stockWithReservation(10, 3);

        assertThrows(NonRetryableEventException.class, () -> apply("{this is not json"));
        assertThrows(NonRetryableEventException.class, () -> apply(""));
        assertThrows(NonRetryableEventException.class, () -> apply("{}"));

        // Right shape, wrong schema version.
        String futureSchema = releaseEvent(UUID.randomUUID(), productId, 3)
                .replace("\"schemaVersion\":1", "\"schemaVersion\":9");
        assertThrows(NonRetryableEventException.class, () -> apply(futureSchema));

        // Right shape, an event type this consumer does not act on.
        String otherType = releaseEvent(UUID.randomUUID(), productId, 3)
                .replace("\"InventoryReleaseRequested\"", "\"InventoryReservationExpired\"");
        assertThrows(NonRetryableEventException.class, () -> apply(otherType));

        assertEquals(3, stockOf(productId).getReservedQuantity(), "no rejected record moved stock");
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_event", Integer.class));
    }

    @Test
    void anEmptyOrNonPositiveReleaseIsRejected() {
        UUID productId = stockWithReservation(10, 3);

        assertThrows(NonRetryableEventException.class,
                () -> apply(releaseEvent(UUID.randomUUID(), orderId, List.of())));
        assertThrows(NonRetryableEventException.class,
                () -> apply(releaseEvent(UUID.randomUUID(), productId, 0)));
        assertThrows(NonRetryableEventException.class,
                () -> apply(releaseEvent(UUID.randomUUID(), productId, -3)));

        assertEquals(3, stockOf(productId).getReservedQuantity());
    }
}
