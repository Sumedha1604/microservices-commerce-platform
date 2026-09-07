/**
 * Outbound Kafka publication for payment lifecycle events.
 *
 * <p>Flow: {@code PaymentServiceImpl} raises an internal Spring application event
 * <em>inside</em> the payment transaction; {@link com.sumedha.commerce.payment.messaging.PaymentEventRelay}
 * reacts {@code AFTER_COMMIT} and asks {@link com.sumedha.commerce.payment.messaging.PaymentEventPublisher}
 * to send the {@code common-events} contract to {@code payment.events.v1}.
 *
 * <p><strong>Reliability boundary:</strong> this is not a transactional outbox. The DB
 * commit and the Kafka send are separate steps; a crash in the window between them loses
 * the event. Closing that gap is the later Outbox milestone.
 */
package com.sumedha.commerce.payment.messaging;
