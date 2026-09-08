package com.sumedha.commerce.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checkout, then authorize the payment: the order reaches CONFIRMED across the Kafka hop.
 *
 * <p>This picks up exactly where {@code CheckoutHappyPathE2ETest} stops. That test asserts the
 * synchronous outcome - order and payment both PENDING - and stays true whether or not a broker
 * is running. This one continues past it and asserts the asynchronous consequence, which only
 * holds once payment-service has published to {@code payment.events.v1} and order-service has
 * consumed it.
 *
 * <p>Opt-in: the Kafka overlay is not part of the base E2E stack, so this is skipped unless
 * {@code -De2e.kafka=true} is passed. Without the overlay the durable outbox remains pending and
 * the order stays PENDING - which would be a real failure of this assertion, not of the
 * synchronous checkout path.
 */
@EnabledIfSystemProperty(named = "e2e.kafka", matches = "true")
class CheckoutPaymentAuthorizationE2ETest extends E2ETestBase {

    private final PaymentEventE2ESupport api = new PaymentEventE2ESupport(serviceUrls);

    @Test
    void authorizingTheCheckoutPaymentConfirmsTheOrder() throws Exception {
        PaymentEventE2ESupport.Checkout checkout = api.checkout();

        JsonNode authorized = api.authorizePayment(checkout.paymentId());
        assertEquals("AUTHORIZED", authorized.path("status").asText());
        assertEquals(checkout.orderId(), api.uuid(authorized, "orderId"));

        JsonNode order = api.awaitOrderStatus(checkout.orderId(), "CONFIRMED");
        assertEquals(checkout.userId(), api.uuid(order, "userId"));
        api.assertMoney(PaymentEventE2ESupport.EXPECTED_TOTAL, order, "total");
    }
}
