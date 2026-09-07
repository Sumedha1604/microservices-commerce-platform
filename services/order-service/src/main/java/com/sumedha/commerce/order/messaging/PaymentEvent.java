package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;

import java.util.UUID;

/**
 * A validated inbound event: envelope identity plus a payload already bound to a concrete
 * contract type. Produced by {@link PaymentEventParser}, consumed by {@link PaymentEventProcessor}.
 */
public sealed interface PaymentEvent {

    UUID eventId();

    String eventType();

    UUID orderId();

    record Authorized(UUID eventId, PaymentAuthorizedEvent payload) implements PaymentEvent {

        @Override
        public String eventType() {
            return EventTypes.PAYMENT_AUTHORIZED;
        }

        @Override
        public UUID orderId() {
            return payload.orderId();
        }
    }

    record Failed(UUID eventId, PaymentFailedEvent payload) implements PaymentEvent {

        @Override
        public String eventType() {
            return EventTypes.PAYMENT_FAILED;
        }

        @Override
        public UUID orderId() {
            return payload.orderId();
        }
    }
}
