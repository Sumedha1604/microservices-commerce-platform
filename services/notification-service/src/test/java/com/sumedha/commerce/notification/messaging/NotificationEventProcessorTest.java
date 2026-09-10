package com.sumedha.commerce.notification.messaging;

import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.notification.entity.Notification;
import com.sumedha.commerce.notification.enums.NotificationType;
import com.sumedha.commerce.notification.repository.NotificationRepository;
import com.sumedha.commerce.notification.repository.ProcessedEventRepository;
import com.sumedha.commerce.notification.service.NotificationFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationEventProcessorTest {

    private ProcessedEventRepository processedEvents;
    private NotificationRepository notifications;
    private NotificationEventProcessor processor;

    private final PaymentEvent.Failed event = new PaymentEvent.Failed(UUID.randomUUID(), Instant.now(),
            new PaymentFailedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "card declined"));

    @BeforeEach
    void setUp() {
        processedEvents = mock(ProcessedEventRepository.class);
        notifications = mock(NotificationRepository.class);
        processor = new NotificationEventProcessor(processedEvents, notifications, new NotificationFactory());
    }

    @Test
    void aFirstDeliveryClaimsTheEventThenRecordsTheNotification() {
        when(processedEvents.insertIfAbsent(event.eventId(), "PaymentFailed", event.orderId())).thenReturn(1);
        when(notifications.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        NotificationEventProcessor.Result result = processor.process(event);

        InOrder order = inOrder(processedEvents, notifications);
        order.verify(processedEvents).insertIfAbsent(event.eventId(), "PaymentFailed", event.orderId());
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        order.verify(notifications).saveAndFlush(saved.capture());

        assertEquals(NotificationEventProcessor.Outcome.CREATED, result.outcome());
        assertEquals(saved.getValue().getId(), result.notificationId());
        assertEquals(NotificationType.PAYMENT_FAILED, result.notificationType());
        assertEquals(event.eventId(), saved.getValue().getEventId());
    }

    @Test
    void aDuplicateWritesNothing() {
        when(processedEvents.insertIfAbsent(any(), any(), any())).thenReturn(0);

        NotificationEventProcessor.Result result = processor.process(event);

        assertEquals(NotificationEventProcessor.Outcome.DUPLICATE, result.outcome());
        assertNull(result.notificationId());
        verifyNoInteractions(notifications);
    }

    /** The exception must escape so the surrounding transaction rolls the claim back. */
    @Test
    void aFailedInsertPropagatesRatherThanBeingSwallowed() {
        when(processedEvents.insertIfAbsent(any(), any(), any())).thenReturn(1);
        when(notifications.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("boom"));

        assertThrows(DataIntegrityViolationException.class, () -> processor.process(event));
        verify(processedEvents).insertIfAbsent(event.eventId(), "PaymentFailed", event.orderId());
    }
}
