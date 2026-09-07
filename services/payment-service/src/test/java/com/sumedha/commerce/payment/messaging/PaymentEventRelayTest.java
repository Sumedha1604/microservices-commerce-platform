package com.sumedha.commerce.payment.messaging;

import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * The relay maps internal transition signals to the {@code common-events} contract and, per
 * the after-commit boundary, never lets a publication failure escape.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventRelayTest {

    @Mock
    PaymentEventPublisher publisher;

    @InjectMocks
    PaymentEventRelay relay;

    private final UUID paymentId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void mapsAuthorizedInternalEventToContractPayload() {
        relay.onPaymentAuthorized(new PaymentAuthorizedInternalEvent(
                paymentId, orderId, userId, new BigDecimal("59.97"), "USD"));

        ArgumentCaptor<PaymentAuthorizedEvent> captor = ArgumentCaptor.forClass(PaymentAuthorizedEvent.class);
        verify(publisher).publishPaymentAuthorized(captor.capture());
        PaymentAuthorizedEvent payload = captor.getValue();
        assertEquals(paymentId, payload.paymentId());
        assertEquals(orderId, payload.orderId());
        assertEquals(userId, payload.userId());
        assertEquals(new BigDecimal("59.97"), payload.amount());
        assertEquals("USD", payload.currency());
    }

    @Test
    void mapsFailedInternalEventToContractPayload() {
        relay.onPaymentFailed(new PaymentFailedInternalEvent(paymentId, orderId, userId, "card declined"));

        ArgumentCaptor<PaymentFailedEvent> captor = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(publisher).publishPaymentFailed(captor.capture());
        assertEquals("card declined", captor.getValue().failureReason());
        assertEquals(orderId, captor.getValue().orderId());
    }

    @Test
    void swallowsPublisherFailureSoTheCommittedPaymentIsNeverRolledBack() {
        doThrow(new RuntimeException("publisher blew up"))
                .when(publisher).publishPaymentAuthorized(org.mockito.ArgumentMatchers.any());

        assertDoesNotThrow(() -> relay.onPaymentAuthorized(new PaymentAuthorizedInternalEvent(
                paymentId, orderId, userId, new BigDecimal("1.00"), "USD")));
    }

    @Test
    void authorizedHandlerDoesNotTouchTheFailedPath() {
        relay.onPaymentAuthorized(new PaymentAuthorizedInternalEvent(
                paymentId, orderId, userId, new BigDecimal("1.00"), "USD"));

        verify(publisher).publishPaymentAuthorized(org.mockito.ArgumentMatchers.any());
        // no publishPaymentFailed
        org.mockito.Mockito.verify(publisher, org.mockito.Mockito.never())
                .publishPaymentFailed(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void relayHasTransactionalEventListenerAfterCommitOnBothHandlers() throws NoSuchMethodException {
        var authorized = PaymentEventRelay.class.getMethod("onPaymentAuthorized", PaymentAuthorizedInternalEvent.class);
        var failed = PaymentEventRelay.class.getMethod("onPaymentFailed", PaymentFailedInternalEvent.class);

        assertAfterCommitListener(authorized);
        assertAfterCommitListener(failed);
    }

    private static void assertAfterCommitListener(java.lang.reflect.Method method) {
        var annotation = method.getAnnotation(org.springframework.transaction.event.TransactionalEventListener.class);
        org.junit.jupiter.api.Assertions.assertNotNull(annotation,
                () -> method.getName() + " must be @TransactionalEventListener");
        assertEquals(org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT, annotation.phase(),
                () -> method.getName() + " must fire AFTER_COMMIT");
    }
}
