package com.sumedha.commerce.notification.service;

import com.sumedha.commerce.notification.entity.Notification;
import com.sumedha.commerce.notification.enums.NotificationChannel;
import com.sumedha.commerce.notification.enums.NotificationType;
import com.sumedha.commerce.notification.messaging.PaymentEvent;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Builds the notification a payment event warrants.
 *
 * <p>The wording states only what the event proves. A {@code PaymentAuthorized} proves the payment
 * was authorized - not that the order was confirmed, which is order-service's decision - so the
 * text never says "order confirmed" or "order cancelled". Nothing here claims the message was
 * sent: it is an {@link NotificationChannel#INTERNAL} record.
 *
 * <p>Plain string templates on purpose; there is no template engine or template store.
 */
@Component
public class NotificationFactory {

    /** Matches the bound payment-service applies to {@code failureReason}; defends the record if a producer does not. */
    static final int MAX_REASON_LENGTH = 500;

    public Notification create(PaymentEvent event) {
        return switch (event) {
            case PaymentEvent.Authorized authorized -> authorized(authorized);
            case PaymentEvent.Failed failed -> failed(failed);
        };
    }

    private static Notification authorized(PaymentEvent.Authorized event) {
        String subject = "Payment authorized for order " + event.orderId();
        String message = "Your payment of " + event.payload().amount().toPlainString() + " "
                + event.payload().currency().toUpperCase(Locale.ROOT) + " for order " + event.orderId()
                + " was authorized (payment " + event.paymentId() + ").";
        return notification(event, NotificationType.PAYMENT_AUTHORIZED, subject, message);
    }

    private static Notification failed(PaymentEvent.Failed event) {
        String subject = "Payment failed for order " + event.orderId();
        StringBuilder message = new StringBuilder("Your payment for order ").append(event.orderId())
                .append(" could not be completed (payment ").append(event.paymentId()).append(").");
        String reason = reason(event.payload().failureReason());
        if (reason != null) {
            message.append(" Reason: ").append(reason);
        }
        return notification(event, NotificationType.PAYMENT_FAILED, subject, message.toString());
    }

    private static Notification notification(PaymentEvent event, NotificationType type,
                                             String subject, String message) {
        return new Notification(event.eventId(), event.eventType(), event.occurredAt(),
                event.orderId(), event.paymentId(), event.userId(),
                NotificationChannel.INTERNAL, type, subject, message);
    }

    private static String reason(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            return null;
        }
        String trimmed = failureReason.strip();
        return trimmed.length() > MAX_REASON_LENGTH ? trimmed.substring(0, MAX_REASON_LENGTH) : trimmed;
    }
}
