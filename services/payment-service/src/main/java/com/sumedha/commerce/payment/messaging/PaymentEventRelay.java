package com.sumedha.commerce.payment.messaging;

import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges the in-process payment transition signals to the outbound Kafka contract, firing
 * only once the payment transaction has committed.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT} means:
 * <ul>
 *   <li>a rolled-back transition publishes nothing (the listener never runs);</li>
 *   <li>a publication failure here cannot and must not roll back the committed payment -
 *       it is logged and dropped (known commit&rarr;publish gap, closed by the Outbox milestone).</li>
 * </ul>
 */
@Component
public class PaymentEventRelay {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventRelay.class);

    private final PaymentEventPublisher publisher;

    public PaymentEventRelay(PaymentEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentAuthorized(PaymentAuthorizedInternalEvent event) {
        try {
            publisher.publishPaymentAuthorized(new PaymentAuthorizedEvent(
                    event.paymentId(), event.orderId(), event.userId(), event.amount(), event.currency()));
        } catch (RuntimeException ex) {
            log.error("Post-commit relay of PaymentAuthorized for order {} failed; the payment "
                    + "is already committed and is not rolled back", event.orderId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailed(PaymentFailedInternalEvent event) {
        try {
            publisher.publishPaymentFailed(new PaymentFailedEvent(
                    event.paymentId(), event.orderId(), event.userId(), event.failureReason()));
        } catch (RuntimeException ex) {
            log.error("Post-commit relay of PaymentFailed for order {} failed; the payment "
                    + "is already committed and is not rolled back", event.orderId(), ex);
        }
    }
}
