package com.sumedha.commerce.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checkout, then fail the payment: the order reaches CANCELLED across the payment hop, and the
 * stock checkout reserved comes back across the compensation hop.
 *
 * <p>Opt-in for the same reason as {@link CheckoutPaymentAuthorizationE2ETest}: skipped unless
 * {@code -De2e.kafka=true} is passed with the Kafka overlay running for payment, order and
 * inventory.
 *
 * <p>The full saga path under test: checkout reserves -> payment fails -> {@code PaymentFailed}
 * on {@code payment.events.v1} -> order CANCELLED plus an outbox row -> {@code
 * InventoryReleaseRequested} on {@code order.compensation.v1} -> reservation released.
 */
@EnabledIfSystemProperty(named = "e2e.kafka", matches = "true")
class CheckoutPaymentFailedE2ETest extends E2ETestBase {

    private static final String FAILURE_REASON = "E2E: declined by issuer";

    private final PaymentEventE2ESupport api = new PaymentEventE2ESupport(serviceUrls);

    @Test
    void failingTheCheckoutPaymentCancelsTheOrderAndReleasesItsInventory() throws Exception {
        PaymentEventE2ESupport.Checkout checkout = api.checkout();
        assertEquals(PaymentEventE2ESupport.QUANTITY,
                api.inventoryForProduct(checkout.productId()).path("reservedQuantity").asInt(),
                "checkout reserved the cart quantity");

        JsonNode failed = api.failPayment(checkout.paymentId(), FAILURE_REASON);
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals(FAILURE_REASON, failed.path("failureReason").asText());

        JsonNode order = api.awaitOrderStatus(checkout.orderId(), "CANCELLED");
        assertEquals(checkout.userId(), api.uuid(order, "userId"));

        JsonNode inventory = api.awaitReservedQuantity(checkout.productId(), 0);
        assertEquals(PaymentEventE2ESupport.STOCKED_QUANTITY, inventory.path("quantity").asInt(),
                "compensation releases the hold; it never changes owned stock");
        assertEquals(PaymentEventE2ESupport.STOCKED_QUANTITY, inventory.path("availableQuantity").asInt(),
                "every reserved unit is available again");
    }
}
