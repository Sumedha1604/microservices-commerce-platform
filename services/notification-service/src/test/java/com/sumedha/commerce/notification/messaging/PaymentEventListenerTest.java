package com.sumedha.commerce.notification.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.notification.enums.NotificationType;
import com.sumedha.commerce.notification.metrics.NotificationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The listener's contract with the container and with the metrics registry. */
class PaymentEventListenerTest {

    private SimpleMeterRegistry registry;
    private NotificationEventProcessor processor;
    private PaymentEventListener listener;

    private final String validRecord = JsonMapper.builder().build().writeValueAsString(new EventEnvelope<>(
            UUID.randomUUID(), EventTypes.PAYMENT_AUTHORIZED, 1, Instant.now(),
            new PaymentAuthorizedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    new BigDecimal("10.00"), "USD")));

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        processor = mock(NotificationEventProcessor.class);
        listener = new PaymentEventListener(new PaymentEventParser(), processor, new NotificationMetrics(registry));
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    private double created(NotificationType type) {
        return registry.get("notification.persisted").tag("type", type.name()).counter().count();
    }

    @Test
    void aCreatedNotificationIsCountedByType() {
        when(processor.process(any())).thenReturn(new NotificationEventProcessor.Result(
                NotificationEventProcessor.Outcome.CREATED, UUID.randomUUID(), NotificationType.PAYMENT_AUTHORIZED));

        listener.onPaymentEvent(validRecord, "key");

        assertEquals(1, count("notification.events.received"));
        assertEquals(1, created(NotificationType.PAYMENT_AUTHORIZED));
        assertEquals(0, created(NotificationType.PAYMENT_FAILED));
        assertEquals(0, count("notification.duplicate.ignored"));
        assertEquals(0, count("notification.failed"));
    }

    @Test
    void aDuplicateIsCountedAsIgnored() {
        when(processor.process(any())).thenReturn(new NotificationEventProcessor.Result(
                NotificationEventProcessor.Outcome.DUPLICATE, null, null));

        listener.onPaymentEvent(validRecord, "key");

        assertEquals(1, count("notification.duplicate.ignored"));
        assertEquals(0, created(NotificationType.PAYMENT_AUTHORIZED));
    }

    @Test
    void anUnreadableRecordIsCountedAndRethrownWithoutReachingTheProcessor() {
        assertThrows(NonRetryableEventException.class, () -> listener.onPaymentEvent("{not json", "key"));

        assertEquals(1, count("notification.events.received"));
        assertEquals(1, count("notification.failed"));
        verifyNoInteractions(processor);
    }

    @Test
    void aTransientFailureIsRethrownSoTheContainerCanRetryIt() {
        when(processor.process(any())).thenThrow(new TransientDataAccessResourceException("db blip"));

        assertThrows(TransientDataAccessResourceException.class, () -> listener.onPaymentEvent(validRecord, "key"));

        assertEquals(1, count("notification.failed"));
    }

    /** Cardinality is fixed: one created series per notification type and nothing per event. */
    @Test
    void metricCardinalityIsBoundedByTheNotificationTypes() {
        assertEquals(NotificationType.values().length, registry.find("notification.persisted").counters().size());
        assertEquals(1, registry.find("notification.events.received").counters().size());
        assertEquals(1, registry.find("notification.duplicate.ignored").counters().size());
        assertEquals(1, registry.find("notification.failed").counters().size());
    }
}
