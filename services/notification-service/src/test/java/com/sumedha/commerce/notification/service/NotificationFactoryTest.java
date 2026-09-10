package com.sumedha.commerce.notification.service;

import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import com.sumedha.commerce.notification.entity.Notification;
import com.sumedha.commerce.notification.enums.NotificationChannel;
import com.sumedha.commerce.notification.enums.NotificationStatus;
import com.sumedha.commerce.notification.enums.NotificationType;
import com.sumedha.commerce.notification.messaging.PaymentEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationFactoryTest {

    private final UUID eventId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant occurredAt = Instant.parse("2026-09-11T10:15:30Z");

    private final NotificationFactory factory = new NotificationFactory();

    private PaymentEvent.Authorized authorized(String amount, String currency) {
        return new PaymentEvent.Authorized(eventId, occurredAt,
                new PaymentAuthorizedEvent(paymentId, orderId, userId, new BigDecimal(amount), currency));
    }

    private PaymentEvent.Failed failed(String reason) {
        return new PaymentEvent.Failed(eventId, occurredAt, new PaymentFailedEvent(paymentId, orderId, userId, reason));
    }

    @Test
    void anAuthorizationBecomesAPaymentAuthorizedInternalRecord() {
        Notification notification = factory.create(authorized("59.97", "usd"));

        assertNotNull(notification.getId());
        assertEquals(eventId, notification.getEventId());
        assertEquals("PaymentAuthorized", notification.getEventType());
        assertEquals(occurredAt, notification.getOccurredAt());
        assertEquals(orderId, notification.getOrderId());
        assertEquals(paymentId, notification.getPaymentId());
        assertEquals(userId, notification.getUserId());
        assertEquals(NotificationType.PAYMENT_AUTHORIZED, notification.getNotificationType());
        assertEquals(NotificationChannel.INTERNAL, notification.getChannel());
        assertEquals(NotificationStatus.CREATED, notification.getStatus());
        assertEquals(notification.getCreatedAt(), notification.getUpdatedAt());
    }

    @Test
    void theAuthorizedContentStatesTheAmountAndOrderExactly() {
        Notification notification = factory.create(authorized("50.00", "usd"));

        assertEquals("Payment authorized for order " + orderId, notification.getSubject());
        assertEquals("Your payment of 50.00 USD for order " + orderId + " was authorized (payment " + paymentId + ").",
                notification.getMessage());
    }

    @Test
    void aFailureBecomesAPaymentFailedInternalRecord() {
        Notification notification = factory.create(failed("card declined"));

        assertEquals(eventId, notification.getEventId());
        assertEquals("PaymentFailed", notification.getEventType());
        assertEquals(NotificationType.PAYMENT_FAILED, notification.getNotificationType());
        assertEquals(NotificationChannel.INTERNAL, notification.getChannel());
        assertEquals(NotificationStatus.CREATED, notification.getStatus());
        assertEquals(userId, notification.getUserId());
    }

    @Test
    void theFailedContentIncludesTheReason() {
        Notification notification = factory.create(failed("  card declined  "));

        assertEquals("Payment failed for order " + orderId, notification.getSubject());
        assertEquals("Your payment for order " + orderId + " could not be completed (payment " + paymentId
                + "). Reason: card declined", notification.getMessage());
    }

    @Test
    void aFailureWithoutAReasonSaysNothingAboutOne() {
        assertFalse(factory.create(failed(null)).getMessage().contains("Reason"));
        assertFalse(factory.create(failed("   ")).getMessage().contains("Reason"));
    }

    @Test
    void anOverlongReasonIsBounded() {
        String reason = "x".repeat(NotificationFactory.MAX_REASON_LENGTH + 250);

        String message = factory.create(failed(reason)).getMessage();

        assertTrue(message.endsWith("Reason: " + "x".repeat(NotificationFactory.MAX_REASON_LENGTH)), message);
    }

    /**
     * The service only sees a payment event. It must not claim an order outcome it never observed,
     * nor that anything was sent.
     */
    @Test
    void theWordingNeverClaimsAnOrderOutcomeOrADelivery() {
        for (Notification notification : new Notification[]{factory.create(authorized("1.00", "EUR")), factory.create(failed("declined"))}) {
            String text = (notification.getSubject() + " " + notification.getMessage()).toLowerCase(Locale.ROOT);
            assertFalse(text.contains("confirmed"), text);
            assertFalse(text.contains("cancelled"), text);
            assertFalse(text.contains("sent"), text);
            assertFalse(text.contains("email"), text);
        }
    }

    @Test
    void everyNotificationGetsItsOwnId() {
        assertNotEquals(factory.create(failed("a")).getId(), factory.create(failed("a")).getId());
    }
}
