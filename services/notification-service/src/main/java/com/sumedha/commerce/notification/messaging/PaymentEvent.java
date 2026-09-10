package com.sumedha.commerce.notification.messaging;

import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A validated inbound payment event: envelope identity plus a payload already bound to a concrete
 * contract type. Produced by {@link PaymentEventParser}; by the time one exists, every identifier a
 * notification needs is present.
 */
public sealed interface PaymentEvent {

    UUID eventId();

    String eventType();

    Instant occurredAt();

    UUID paymentId();

    UUID orderId();

    UUID userId();

    record Authorized(UUID eventId, Instant occurredAt, PaymentAuthorizedEvent payload) implements PaymentEvent {

        @Override
        public String eventType() {
            return EventTypes.PAYMENT_AUTHORIZED;
        }

        @Override
        public UUID paymentId() {
            return payload.paymentId();
        }

        @Override
        public UUID orderId() {
            return payload.orderId();
        }

        @Override
        public UUID userId() {
            return payload.userId();
        }
    }

    record Failed(UUID eventId, Instant occurredAt, PaymentFailedEvent payload) implements PaymentEvent {

        @Override
        public String eventType() {
            return EventTypes.PAYMENT_FAILED;
        }

        @Override
        public UUID paymentId() {
            return payload.paymentId();
        }

        @Override
        public UUID orderId() {
            return payload.orderId();
        }

        @Override
        public UUID userId() {
            return payload.userId();
        }
    }
}
