package com.sumedha.commerce.inventory.messaging;

import com.sumedha.commerce.common.events.inventory.InventoryReleaseLine;
import com.sumedha.commerce.inventory.entity.Inventory;
import com.sumedha.commerce.inventory.repository.InventoryRepository;
import com.sumedha.commerce.inventory.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Applies one compensation event to stock, and records that it was applied, in one transaction.
 *
 * <p><strong>Why the marker comes first.</strong> There are no reservation rows in this platform
 * - a reservation is a counter on the inventory row - so there is no reservation status to
 * transition and no per-reservation guard available. The {@code processed_event} primary key is
 * therefore the entire idempotency mechanism, and it is claimed with a guarded insert
 * ({@code on conflict do nothing}) <em>before</em> any stock moves. A second worker holding the
 * same {@code eventId} waits on that insert until the first transaction finishes, then sees the id
 * taken and returns {@link Outcome#DUPLICATE} having touched nothing. No exception has to be
 * interpreted as "probably a duplicate", so a genuine integrity failure can never be mistaken for
 * one.
 *
 * <p><strong>Everything is one transaction.</strong> A multi-line order releases every line or
 * none: if the third line's product has no inventory row, the first two decrements roll back with
 * the marker, and the record is dead-lettered intact rather than leaving stock half-restored.
 *
 * <p><strong>Row locks, in a fixed order.</strong> Each inventory row is loaded
 * {@code FOR UPDATE}, so the "is this much reserved?" check and the decrement cannot be split by
 * another writer. Lines are merged per product and locked in ascending {@code productId} order,
 * so two compensations touching the same products can never deadlock by locking them in opposite
 * orders.
 *
 * <p><strong>Failure classification.</strong> A release that the current stock cannot support -
 * an unknown product, or more units than are actually reserved - is a
 * {@link NonRetryableEventException}, not a retry. Retrying could never make it true, and the
 * alternative to refusing is driving {@code reserved_quantity} negative against its check
 * constraint. Refusing loudly and dead-lettering leaves the numbers correct and the record
 * inspectable.
 */
@Service
public class InventoryReleaseProcessor {

    private static final Logger log = LoggerFactory.getLogger(InventoryReleaseProcessor.class);

    /** What one delivery actually did, for logging and assertions. */
    public enum Outcome {
        /** Stock was released and the marker written. */
        RELEASED,
        /** This exact eventId was already applied; nothing changed. */
        DUPLICATE
    }

    private final InventoryRepository inventories;
    private final ProcessedEventRepository processedEvents;

    public InventoryReleaseProcessor(InventoryRepository inventories,
                                     ProcessedEventRepository processedEvents) {
        this.inventories = inventories;
        this.processedEvents = processedEvents;
    }

    @Transactional
    public Outcome process(InventoryReleaseCommand command) {
        if (processedEvents.insertIfAbsent(command.eventId(), command.eventType(), command.orderId()) == 0) {
            log.info("Compensation duplicate ignored eventId={} orderId={}", command.eventId(), command.orderId());
            return Outcome.DUPLICATE;
        }

        SortedMap<UUID, Integer> quantities = quantitiesByProduct(command);
        for (Map.Entry<UUID, Integer> line : quantities.entrySet()) {
            release(command, line.getKey(), line.getValue());
        }

        log.info("Compensation applied eventId={} orderId={} products={} reason={}",
                command.eventId(), command.orderId(), quantities.size(), command.payload().reason());
        return Outcome.RELEASED;
    }

    /** One entry per product, in lock order. Two lines for one product release their sum. */
    private static SortedMap<UUID, Integer> quantitiesByProduct(InventoryReleaseCommand command) {
        SortedMap<UUID, Integer> quantities = new TreeMap<>();
        for (InventoryReleaseLine line : command.payload().lines()) {
            quantities.merge(line.productId(), line.quantity(), Math::addExact);
        }
        return quantities;
    }

    private void release(InventoryReleaseCommand command, UUID productId, int quantity) {
        Inventory inventory = inventories.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new NonRetryableEventException("Compensation " + command.eventId()
                        + " for order " + command.orderId() + " refers to product " + productId
                        + ", which has no inventory record"));

        if (quantity > inventory.getReservedQuantity()) {
            throw new NonRetryableEventException("Compensation " + command.eventId() + " for order "
                    + command.orderId() + " asks to release " + quantity + " of product "
                    + productId + " but only " + inventory.getReservedQuantity()
                    + " is reserved; refusing rather than driving reserved stock negative");
        }

        inventory.release(quantity);
        log.info("Compensation released eventId={} orderId={} productId={} quantity={} reservedNow={}",
                command.eventId(), command.orderId(), productId, quantity, inventory.getReservedQuantity());
    }
}
