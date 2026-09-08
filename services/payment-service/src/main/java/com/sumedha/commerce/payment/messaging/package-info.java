/**
 * Outbound Kafka publication for payment lifecycle events.
 *
 * <p>Flow: {@code PaymentServiceImpl} serializes and persists the complete wire envelope in
 * the payment transaction. The scheduled outbox publisher later locks a bounded batch, asks
 * {@link com.sumedha.commerce.payment.messaging.PaymentEventPublisher} to send each stored
 * value, and marks it published only after Kafka acknowledges it.
 *
 * <p>Delivery is at-least-once: a crash after acknowledgement but before the outbox status
 * commit can publish the same persisted event id again.
 */
package com.sumedha.commerce.payment.messaging;
