package com.sumedha.commerce.common.events;

/**
 * Canonical {@code eventType} discriminator values carried in {@link EventEnvelope#eventType()}.
 *
 * <p>These strings are part of the wire contract: consumers match on them to decide which
 * payload record to bind. They are intentionally not derived from Java class names.
 */
public final class EventTypes {

    /** {@link com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent} payload. */
    public static final String PAYMENT_AUTHORIZED = "PaymentAuthorized";

    /** {@link com.sumedha.commerce.common.events.payment.PaymentFailedEvent} payload. */
    public static final String PAYMENT_FAILED = "PaymentFailed";

    private EventTypes() {
        throw new UnsupportedOperationException("Constants class");
    }
}
