package com.sumedha.commerce.common.events;

/**
 * Centralised Kafka topic names.
 *
 * <p>Convention: {@code <publishing-domain>.<stream>.v<major>}. A breaking payload change
 * is a new topic with a bumped version suffix, never an in-place edit.
 */
public final class KafkaTopics {

    /** Payment lifecycle domain events (PaymentAuthorized, PaymentFailed). Partition key: orderId. */
    public static final String PAYMENT_EVENTS_V1 = "payment.events.v1";

    /**
     * Dead-letter topic paired with {@link #PAYMENT_EVENTS_V1}. Named here so the contract
     * layer owns the convention; actual dead-letter routing is wired in a later phase.
     */
    public static final String PAYMENT_EVENTS_V1_DLT = "payment.events.v1.DLT";

    private KafkaTopics() {
        throw new UnsupportedOperationException("Constants class");
    }
}
