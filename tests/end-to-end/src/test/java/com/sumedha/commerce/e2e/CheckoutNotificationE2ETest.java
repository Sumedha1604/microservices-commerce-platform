package com.sumedha.commerce.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkout, then settle the payment: notification-service records exactly one notification for the
 * order, from the same {@code payment.events.v1} record order-service acts on.
 *
 * <p>Both consumers are asserted: the order reaches its terminal status (order-service's group) and
 * a notification appears (notification-service's group). Neither waits for the other; they are
 * independent readers of one stream.
 *
 * <p>Opt-in twice over: it needs the Kafka overlay ({@code -De2e.kafka=true}) and notification-service
 * running on it ({@code -De2e.notification=true}), so the existing Kafka E2E command keeps working
 * on a stack without notification-service.
 */
@EnabledIfSystemProperty(named = "e2e.kafka", matches = "true")
@EnabledIfSystemProperty(named = "e2e.notification", matches = "true")
class CheckoutNotificationE2ETest extends E2ETestBase {

    private static final String FAILURE_REASON = "E2E: declined by issuer";
    /** Long enough for a redelivered or doubly-processed event to show up as a second record. */
    private static final Duration STAYS_SINGLE_WINDOW = Duration.ofSeconds(3);

    private final PaymentEventE2ESupport api = new PaymentEventE2ESupport(serviceUrls);

    @Test
    void authorizingTheCheckoutPaymentRecordsOnePaymentAuthorizedNotification() throws Exception {
        PaymentEventE2ESupport.Checkout checkout = api.checkout();

        api.authorizePayment(checkout.paymentId());
        api.awaitOrderStatus(checkout.orderId(), "CONFIRMED");

        JsonNode notification = api.awaitNotificationsForOrder(checkout.orderId(), 1).get(0);
        assertCommonFields(checkout, notification);
        assertEquals("PaymentAuthorized", notification.path("eventType").asText());
        assertEquals("PAYMENT_AUTHORIZED", notification.path("notificationType").asText());
        assertTrue(notification.path("message").asText().contains(PaymentEventE2ESupport.EXPECTED_TOTAL.toPlainString() + " USD"),
                notification.path("message").asText());

        api.assertNotificationCountHolds(checkout.orderId(), 1, STAYS_SINGLE_WINDOW);
    }

    @Test
    void failingTheCheckoutPaymentRecordsOnePaymentFailedNotification() throws Exception {
        PaymentEventE2ESupport.Checkout checkout = api.checkout();

        api.failPayment(checkout.paymentId(), FAILURE_REASON);
        api.awaitOrderStatus(checkout.orderId(), "CANCELLED");

        JsonNode notification = api.awaitNotificationsForOrder(checkout.orderId(), 1).get(0);
        assertCommonFields(checkout, notification);
        assertEquals("PaymentFailed", notification.path("eventType").asText());
        assertEquals("PAYMENT_FAILED", notification.path("notificationType").asText());
        assertTrue(notification.path("message").asText().endsWith("Reason: " + FAILURE_REASON),
                notification.path("message").asText());

        api.assertNotificationCountHolds(checkout.orderId(), 1, STAYS_SINGLE_WINDOW);
    }

    private void assertCommonFields(PaymentEventE2ESupport.Checkout checkout, JsonNode notification) {
        assertEquals(checkout.orderId(), api.uuid(notification, "orderId"));
        assertEquals(checkout.paymentId(), api.uuid(notification, "paymentId"));
        assertEquals(checkout.userId(), api.uuid(notification, "userId"));
        assertEquals("INTERNAL", notification.path("channel").asText());
        assertEquals("CREATED", notification.path("status").asText(), "recorded, not sent");
        api.uuid(notification, "eventId");
        api.uuid(notification, "notificationId");
    }
}
