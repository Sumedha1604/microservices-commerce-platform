package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.entity.OrderItem;
import com.sumedha.commerce.order.entity.ProcessedEvent;
import com.sumedha.commerce.order.metrics.OrderCompensationMetrics;
import com.sumedha.commerce.order.repository.OrderItemRepository;
import com.sumedha.commerce.order.repository.OrderOutboxEventRepository;
import com.sumedha.commerce.order.repository.OrderRepository;
import com.sumedha.commerce.order.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Applies one payment event to its order and records the {@code processed_event} marker in a
 * single database transaction.
 *
 * <p>Everything that can go wrong is classified explicitly rather than by catching
 * {@code ConflictException} and hoping:
 *
 * <ul>
 *   <li><b>Duplicate {@code eventId}</b> - the marker already exists: no-op success, so the
 *       offset advances.</li>
 *   <li><b>Already in the target state under a different {@code eventId}</b> - an authorization
 *       for an already {@code CONFIRMED} order, or a failure for an already {@code CANCELLED}
 *       one: idempotent no-op, but the new {@code eventId} is still recorded as processed.</li>
 *   <li><b>Contradictory state</b> - an authorization for a {@code CANCELLED} order: a real
 *       semantic inconsistency, raised as {@link NonRetryableEventException} so it reaches the
 *       dead-letter topic instead of being hidden as a duplicate.</li>
 *   <li><b>Unknown order</b> - {@link NonRetryableEventException}.</li>
 * </ul>
 *
 * <p>{@code PaymentFailed} on a {@code CONFIRMED} order is valid under the current domain rules
 * ({@code CONFIRMED -> CANCELLED} is allowed) and cancels the order.
 *
 * <p><strong>Compensation.</strong> A cancellation that actually happens here also writes one
 * {@code order_outbox_event} row asking inventory-service to release the stock checkout reserved,
 * in this same transaction. That is the saga step: the order moving to CANCELLED and the promise
 * to release its inventory either both commit or neither does. No Kafka call happens inside this
 * transaction - the durable row is the handoff.
 *
 * <p>Compensation is emitted <em>only</em> from this path, and only on a real
 * {@code PENDING/CONFIRMED -> CANCELLED} transition. It is deliberately not attached to
 * {@code Order.cancel()} itself: checkout-service cancels orders through the HTTP API in its own
 * failure handler, having already released those reservations synchronously, and emitting an
 * event there would release the same stock twice.
 */
@Service
public class PaymentEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventProcessor.class);

    /** What one delivery actually did, for logging and assertions. */
    public enum Outcome {
        /** The order transition was applied and the marker written. */
        APPLIED,
        /** This exact eventId was already processed; nothing changed. */
        DUPLICATE,
        /** A different eventId, but the order was already in the target state; marker written. */
        ALREADY_IN_TARGET_STATE
    }

    private final OrderRepository orders;
    private final ProcessedEventRepository processedEvents;
    private final OrderItemRepository orderItems;
    private final OrderOutboxEventRepository outboxEvents;
    private final OrderOutboxEventFactory outboxEventFactory;
    private final OrderCompensationMetrics compensationMetrics;

    public PaymentEventProcessor(OrderRepository orders,
                                 ProcessedEventRepository processedEvents,
                                 OrderItemRepository orderItems,
                                 OrderOutboxEventRepository outboxEvents,
                                 OrderOutboxEventFactory outboxEventFactory,
                                 OrderCompensationMetrics compensationMetrics) {
        this.orders = orders;
        this.processedEvents = processedEvents;
        this.orderItems = orderItems;
        this.outboxEvents = outboxEvents;
        this.outboxEventFactory = outboxEventFactory;
        this.compensationMetrics = compensationMetrics;
    }

    /**
     * One transaction: duplicate check, order transition, marker insert. A failure anywhere rolls
     * back all of it, so a marker is never left behind for an event that was not applied.
     */
    @Transactional
    public Outcome process(PaymentEvent event) {
        if (processedEvents.existsById(event.eventId())) {
            log.debug("Event {} ({}) for order {} already processed; ignoring redelivery",
                    event.eventId(), event.eventType(), event.orderId());
            return Outcome.DUPLICATE;
        }

        Order order = orders.findById(event.orderId()).orElseThrow(() -> new NonRetryableEventException(
                "Event " + event.eventId() + " (" + event.eventType() + ") refers to unknown order "
                        + event.orderId()));

        Outcome outcome = switch (event) {
            case PaymentEvent.Authorized authorized -> applyAuthorized(order, authorized);
            case PaymentEvent.Failed failed -> applyFailed(order, failed);
        };

        // Flushes the pending order update too, so a constraint or optimistic-lock failure
        // surfaces inside this transaction rather than after it.
        processedEvents.saveAndFlush(
                new ProcessedEvent(event.eventId(), event.eventType(), event.orderId()));
        return outcome;
    }

    private Outcome applyAuthorized(Order order, PaymentEvent.Authorized event) {
        return switch (order.getStatus()) {
            case PENDING -> {
                order.confirm();
                log.info("Order {} CONFIRMED by event {}", order.getId(), event.eventId());
                yield Outcome.APPLIED;
            }
            case CONFIRMED -> {
                log.info("Order {} is already CONFIRMED; event {} is an idempotent no-op",
                        order.getId(), event.eventId());
                yield Outcome.ALREADY_IN_TARGET_STATE;
            }
            case CANCELLED -> throw new NonRetryableEventException("Event " + event.eventId()
                    + " authorizes payment for order " + order.getId() + " but that order is CANCELLED;"
                    + " this is a semantic inconsistency, not a duplicate");
        };
    }

    /**
     * Writes the compensation intent in the caller's transaction.
     *
     * <p>An order with no lines produces no event rather than an empty one: there is nothing to
     * release, and an empty compensation would be noise the consumer has to special-case.
     */
    private void requestInventoryRelease(Order order, PaymentEvent.Failed event) {
        List<OrderItem> items = orderItems.findByOrderId(order.getId());
        if (items.isEmpty()) {
            log.warn("Order {} cancelled by event {} has no line items; no inventory release requested",
                    order.getId(), event.eventId());
            return;
        }

        var outboxEvent = outboxEventFactory.inventoryReleaseRequested(
                order, items, "Payment failed: " + event.payload().failureReason());
        // saveAndFlush so a constraint failure surfaces here, inside the transaction that
        // cancelled the order, rather than after it has already committed.
        outboxEvents.saveAndFlush(outboxEvent);
        compensationMetrics.persisted();
        log.info("Compensation queued eventId={} orderId={} lines={} topic={}",
                outboxEvent.getEventId(), order.getId(), items.size(), outboxEvent.getTopic());
    }

    private Outcome applyFailed(Order order, PaymentEvent.Failed event) {
        return switch (order.getStatus()) {
            // CONFIRMED -> CANCELLED is permitted by the current domain rules.
            case PENDING, CONFIRMED -> {
                order.cancel();
                requestInventoryRelease(order, event);
                log.info("Order {} CANCELLED by event {} (reason: {})",
                        order.getId(), event.eventId(), event.payload().failureReason());
                yield Outcome.APPLIED;
            }
            case CANCELLED -> {
                log.info("Order {} is already CANCELLED; event {} is an idempotent no-op",
                        order.getId(), event.eventId());
                yield Outcome.ALREADY_IN_TARGET_STATE;
            }
        };
    }
}
