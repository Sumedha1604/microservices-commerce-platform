package com.sumedha.commerce.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checkout, then fail the payment: the order reaches CANCELLED across the Kafka hop.
 *
 * <p>Opt-in for the same reason as {@link CheckoutPaymentAuthorizationE2ETest}: skipped unless
 * {@code -De2e.kafka=true} is passed with the Kafka overlay running.
 *
 * <p>This asserts the order side only. Whether the cancellation also releases the inventory
 * reservation is deliberately not asserted: nothing owns that release yet (see the ADR), so the
 * reservation still stands after this test.
 */
@EnabledIfSystemProperty(named = "e2e.kafka", matches = "true")
class CheckoutPaymentFailedE2ETest extends E2ETestBase {

    private static final String FAILURE_REASON = "E2E: declined by issuer";

    private final PaymentEventE2ESupport api = new PaymentEventE2ESupport(serviceUrls);

    @Test
    void failingTheCheckoutPaymentCancelsTheOrder() throws Exception {
        PaymentEventE2ESupport.Checkout checkout = api.checkout();

        JsonNode failed = api.failPayment(checkout.paymentId(), FAILURE_REASON);
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals(FAILURE_REASON, failed.path("failureReason").asText());

        JsonNode order = api.awaitOrderStatus(checkout.orderId(), "CANCELLED");
        assertEquals(checkout.userId(), api.uuid(order, "userId"));
    }
}
