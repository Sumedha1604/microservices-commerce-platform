package com.sumedha.commerce.notification.enums;

import com.sumedha.commerce.common.events.EventTypes;

/**
 * What a notification is about, named after the fact it reports.
 *
 * <p>Types are named for the <em>payment</em> outcome, not the order outcome
 * ({@code ORDER_CONFIRMED}/{@code ORDER_CANCELLED}), because a payment event is all this service
 * sees. Whether order-service actually confirmed or cancelled the order is its own decision - it
 * can, for instance, dead-letter an authorization for an order that is already cancelled - and
 * this service does not call order-service to find out.
 */
public enum NotificationType {

    /** Raised from {@code PaymentAuthorized}. */
    PAYMENT_AUTHORIZED(EventTypes.PAYMENT_AUTHORIZED),

    /** Raised from {@code PaymentFailed}. */
    PAYMENT_FAILED(EventTypes.PAYMENT_FAILED);

    private final String sourceEventType;

    NotificationType(String sourceEventType) {
        this.sourceEventType = sourceEventType;
    }

    /** The {@code eventType} on {@code payment.events.v1} this notification type is raised from. */
    public String sourceEventType() {
        return sourceEventType;
    }
}
