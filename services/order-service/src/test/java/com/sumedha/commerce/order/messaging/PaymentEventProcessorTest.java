package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.entity.OrderItem;
import com.sumedha.commerce.order.entity.OrderOutboxEvent;
import com.sumedha.commerce.order.entity.ProcessedEvent;
import com.sumedha.commerce.order.enums.OrderStatus;
import com.sumedha.commerce.order.metrics.OrderCompensationMetrics;
import com.sumedha.commerce.order.repository.OrderItemRepository;
import com.sumedha.commerce.order.repository.OrderOutboxEventRepository;
import com.sumedha.commerce.order.repository.OrderRepository;
import com.sumedha.commerce.order.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The state-semantics matrix: which deliveries apply, which are idempotent no-ops, and which are
 * genuine inconsistencies that must reach the dead-letter topic.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventProcessorTest {

    @Mock OrderRepository orders;
    @Mock ProcessedEventRepository processedEvents;
    @Mock OrderItemRepository orderItems;
    @Mock OrderOutboxEventRepository outboxEvents;

    PaymentEventProcessor processor;

    UUID eventId;
    UUID paymentId;
    UUID userId;

    @BeforeEach
    void setUp() {
        processor = new PaymentEventProcessor(orders, processedEvents, orderItems, outboxEvents,
                new OrderOutboxEventFactory(), new OrderCompensationMetrics(new SimpleMeterRegistry()));
        eventId = UUID.randomUUID();
        paymentId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    private Order pendingOrder() {
        return new Order(userId, new BigDecimal("59.97"), new BigDecimal("59.97"), "USD");
    }

    private PaymentEvent.Authorized authorized(Order order) {
        return new PaymentEvent.Authorized(eventId,
                new PaymentAuthorizedEvent(paymentId, order.getId(), userId, new BigDecimal("59.97"), "USD"));
    }

    private PaymentEvent.Failed failed(Order order) {
        return new PaymentEvent.Failed(eventId,
                new PaymentFailedEvent(paymentId, order.getId(), userId, "card declined"));
    }

    private void given(Order order) {
        when(processedEvents.existsById(eventId)).thenReturn(false);
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
    }

    /** A cancellation only emits compensation when the order actually has lines to release. */
    private void givenOneOrderLine(Order order) {
        when(orderItems.findByOrderId(order.getId())).thenReturn(List.of(new OrderItem(
                order.getId(), UUID.randomUUID(), "Widget", "sku-1", new BigDecimal("19.99"), 3,
                new BigDecimal("59.97"))));
    }

    private OrderOutboxEvent capturedCompensation() {
        ArgumentCaptor<OrderOutboxEvent> captor = ArgumentCaptor.forClass(OrderOutboxEvent.class);
        verify(outboxEvents).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private ProcessedEvent capturedMarker() {
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    // ---- A: duplicate eventId ----

    @Test
    void duplicateEventIdIsANoOpSuccessAndNeverTouchesTheOrder() {
        when(processedEvents.existsById(eventId)).thenReturn(true);
        Order order = pendingOrder();

        assertEquals(PaymentEventProcessor.Outcome.DUPLICATE, processor.process(authorized(order)));

        verify(orders, never()).findById(any());
        verify(processedEvents, never()).saveAndFlush(any());
        assertEquals(OrderStatus.PENDING, order.getStatus());
    }

    // ---- PaymentAuthorized ----

    @Test
    void authorizedConfirmsAPendingOrderAndRecordsTheMarker() {
        Order order = pendingOrder();
        given(order);

        assertEquals(PaymentEventProcessor.Outcome.APPLIED, processor.process(authorized(order)));

        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        ProcessedEvent marker = capturedMarker();
        assertEquals(eventId, marker.getEventId());
        assertEquals("PaymentAuthorized", marker.getEventType());
        assertEquals(order.getId(), marker.getOrderId());
    }

    @Test
    void authorizedOnAnAlreadyConfirmedOrderIsIdempotentButStillRecordsTheNewEventId() {
        Order order = pendingOrder();
        order.confirm();
        given(order);

        assertEquals(PaymentEventProcessor.Outcome.ALREADY_IN_TARGET_STATE, processor.process(authorized(order)));

        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        assertEquals(eventId, capturedMarker().getEventId());
    }

    @Test
    void authorizedOnACancelledOrderIsANonRetryableSemanticInconsistency() {
        Order order = pendingOrder();
        order.cancel();
        given(order);

        NonRetryableEventException thrown =
                assertThrows(NonRetryableEventException.class, () -> processor.process(authorized(order)));

        assertTrue(thrown.getMessage().contains("semantic inconsistency"));
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(processedEvents, never()).saveAndFlush(any());
    }

    // ---- PaymentFailed ----

    @Test
    void failedCancelsAPendingOrderAndRecordsTheMarker() {
        Order order = pendingOrder();
        given(order);
        givenOneOrderLine(order);

        assertEquals(PaymentEventProcessor.Outcome.APPLIED, processor.process(failed(order)));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals("PaymentFailed", capturedMarker().getEventType());
    }

    @Test
    void failedCancelsAConfirmedOrderBecauseTheDomainAllowsThatTransition() {
        Order order = pendingOrder();
        order.confirm();
        given(order);
        givenOneOrderLine(order);

        assertEquals(PaymentEventProcessor.Outcome.APPLIED, processor.process(failed(order)));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(eventId, capturedMarker().getEventId());
    }

    @Test
    void failedOnAnAlreadyCancelledOrderIsIdempotentButStillRecordsTheNewEventId() {
        Order order = pendingOrder();
        order.cancel();
        given(order);

        assertEquals(PaymentEventProcessor.Outcome.ALREADY_IN_TARGET_STATE, processor.process(failed(order)));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(eventId, capturedMarker().getEventId());
    }

    // ---- unknown order ----

    @Test
    void unknownOrderIsNonRetryableAndRecordsNoMarker() {
        UUID missing = UUID.randomUUID();
        when(processedEvents.existsById(eventId)).thenReturn(false);
        when(orders.findById(missing)).thenReturn(Optional.empty());
        PaymentEvent.Authorized event = new PaymentEvent.Authorized(eventId,
                new PaymentAuthorizedEvent(paymentId, missing, userId, new BigDecimal("1.00"), "USD"));

        NonRetryableEventException thrown =
                assertThrows(NonRetryableEventException.class, () -> processor.process(event));

        assertTrue(thrown.getMessage().contains("unknown order"));
        verify(processedEvents, never()).saveAndFlush(any());
    }

    // ---- marker persistence failure must not leave a half-applied transition ----

    @Test
    void aMarkerPersistenceFailurePropagatesSoTheTransactionRollsBack() {
        Order order = pendingOrder();
        given(order);
        when(processedEvents.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> processor.process(authorized(order)));
    }

    // ---- compensation: which cancellations ask for inventory back, and which must not ----

    @Test
    void aCancellationCausedByPaymentFailureQueuesExactlyOneCompensationEvent() {
        Order order = pendingOrder();
        given(order);
        givenOneOrderLine(order);

        processor.process(failed(order));

        OrderOutboxEvent compensation = capturedCompensation();
        assertEquals("InventoryReleaseRequested", compensation.getEventType());
        assertEquals("order.compensation.v1", compensation.getTopic());
        assertEquals(order.getId(), compensation.getAggregateId());
        assertEquals(order.getId().toString(), compensation.getEventKey(),
                "keyed by orderId so compensation for one order stays ordered");
        assertEquals(1, compensation.getSchemaVersion());
        assertTrue(compensation.getPayload().contains("InventoryReleaseRequested"));
        assertTrue(compensation.getPayload().contains("card declined"),
                "the failure reason travels with the intent for operator triage");
    }

    /** The whole point of the milestone: a successful payment must never release stock. */
    @Test
    void anAuthorizedPaymentNeverQueuesCompensation() {
        Order order = pendingOrder();
        given(order);

        assertEquals(PaymentEventProcessor.Outcome.APPLIED, processor.process(authorized(order)));

        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        verify(outboxEvents, never()).saveAndFlush(any());
    }

    @Test
    void anAuthorizationForAnAlreadyConfirmedOrderQueuesNoCompensationEither() {
        Order order = pendingOrder();
        order.confirm();
        given(order);

        processor.process(authorized(order));

        verify(outboxEvents, never()).saveAndFlush(any());
    }

    /**
     * A redelivery that finds the order already cancelled did not cancel anything, so it has no
     * reservation to compensate - the compensation for that cancellation was queued the first
     * time. Emitting another would release the same stock twice.
     */
    @Test
    void aFailureForAnAlreadyCancelledOrderQueuesNoSecondCompensation() {
        Order order = pendingOrder();
        order.cancel();
        given(order);

        assertEquals(PaymentEventProcessor.Outcome.ALREADY_IN_TARGET_STATE, processor.process(failed(order)));

        verify(outboxEvents, never()).saveAndFlush(any());
    }

    @Test
    void aDuplicateEventIdIsRejectedBeforeAnyCompensationIsConsidered() {
        Order order = pendingOrder();
        when(processedEvents.existsById(eventId)).thenReturn(true);

        assertEquals(PaymentEventProcessor.Outcome.DUPLICATE, processor.process(failed(order)));

        verify(outboxEvents, never()).saveAndFlush(any());
        verify(orderItems, never()).findByOrderId(any());
    }

    /** An order with no lines has nothing to release; an empty compensation would be noise. */
    @Test
    void anOrderWithNoLinesIsCancelledWithoutQueueingAnEmptyCompensation() {
        Order order = pendingOrder();
        given(order);

        assertEquals(PaymentEventProcessor.Outcome.APPLIED, processor.process(failed(order)));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(outboxEvents, never()).saveAndFlush(any());
    }

    @Test
    void everyOrderLineBecomesAReleaseLine() {
        Order order = pendingOrder();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        given(order);
        when(orderItems.findByOrderId(order.getId())).thenReturn(List.of(
                new OrderItem(order.getId(), productA, "Widget", "sku-1", new BigDecimal("19.99"), 3,
                        new BigDecimal("59.97")),
                new OrderItem(order.getId(), productB, "Gadget", "sku-2", new BigDecimal("5.00"), 2,
                        new BigDecimal("10.00"))));

        processor.process(failed(order));

        String payload = capturedCompensation().getPayload();
        assertTrue(payload.contains(productA.toString()), payload);
        assertTrue(payload.contains(productB.toString()), payload);
    }
}
