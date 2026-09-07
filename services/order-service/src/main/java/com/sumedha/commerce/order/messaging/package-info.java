/**
 * Inbound Kafka consumption of payment lifecycle events.
 *
 * <p>Flow: {@link com.sumedha.commerce.order.messaging.PaymentEventListener} receives the raw
 * JSON string, {@link com.sumedha.commerce.order.messaging.PaymentEventParser} validates the
 * envelope and binds the payload to an explicit type, and
 * {@link com.sumedha.commerce.order.messaging.PaymentEventProcessor} applies the order
 * transition and the {@code processed_event} marker in one database transaction.
 *
 * <p>The value is consumed as a plain {@code String} and bound by an explicitly named type - no
 * {@code __TypeId__} header, no default typing, no class-name driven deserialization.
 *
 * <p>Delivery is at-least-once: the Kafka offset commit and the database commit are not atomic,
 * so redelivery is expected and made safe by the {@code processed_event} primary key rather than
 * by any transactional-messaging mechanism.
 */
package com.sumedha.commerce.order.messaging;
