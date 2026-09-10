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

    /**
     * notification-service's own dead-letter topic for {@link #PAYMENT_EVENTS_V1}.
     *
     * <p>Dead-letter topics belong to the consumer, not the stream: {@link #PAYMENT_EVENTS_V1_DLT}
     * is order-service's, and its inspection/replay tooling ingests everything on it. A second
     * consumer of the same stream therefore gets a consumer-qualified name, so a record only
     * notification-service rejected is never captured - or replayed - as an order-service failure.
     */
    public static final String PAYMENT_EVENTS_V1_NOTIFICATION_DLT = "payment.events.v1.notification.DLT";

    /**
     * Inventory-compensation intents published by order-service (InventoryReleaseRequested).
     * Partition key: orderId, so all compensation for one order stays ordered.
     */
    public static final String ORDER_COMPENSATION_V1 = "order.compensation.v1";

    /**
     * Dead-letter topic paired with {@link #ORDER_COMPENSATION_V1}, declared by its consumer
     * (inventory-service) exactly as order-service declares the payment dead-letter topic.
     */
    public static final String ORDER_COMPENSATION_V1_DLT = "order.compensation.v1.DLT";

    private KafkaTopics() {
        throw new UnsupportedOperationException("Constants class");
    }
}
