package com.sumedha.commerce.common.events.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Field-level construction and JSON round-trip for the payment payload records, with
 * particular attention to money fields (exact decimal, no float rounding).
 */
class PaymentEventPayloadTest {

    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void paymentAuthorizedExposesEveryConstructorField() {
        PaymentAuthorizedEvent event =
                new PaymentAuthorizedEvent(PAYMENT_ID, ORDER_ID, USER_ID, new BigDecimal("59.97"), "USD");

        assertEquals(PAYMENT_ID, event.paymentId());
        assertEquals(ORDER_ID, event.orderId());
        assertEquals(USER_ID, event.userId());
        assertEquals(new BigDecimal("59.97"), event.amount());
        assertEquals("USD", event.currency());
    }

    @Test
    void paymentAuthorizedRoundTripsAsJson() throws Exception {
        PaymentAuthorizedEvent event =
                new PaymentAuthorizedEvent(PAYMENT_ID, ORDER_ID, USER_ID, new BigDecimal("1234.50"), "EUR");

        PaymentAuthorizedEvent back =
                mapper.readValue(mapper.writeValueAsString(event), PaymentAuthorizedEvent.class);

        assertEquals(event, back);
    }

    @Test
    void paymentFailedRoundTripsAsJson() throws Exception {
        PaymentFailedEvent event =
                new PaymentFailedEvent(PAYMENT_ID, ORDER_ID, USER_ID, "card declined by issuer");

        PaymentFailedEvent back =
                mapper.readValue(mapper.writeValueAsString(event), PaymentFailedEvent.class);

        assertEquals(event, back);
        assertEquals("card declined by issuer", back.failureReason());
    }

    @Test
    void paymentFailedAllowsNullFailureReason() throws Exception {
        PaymentFailedEvent event = new PaymentFailedEvent(PAYMENT_ID, ORDER_ID, USER_ID, null);

        PaymentFailedEvent back =
                mapper.readValue(mapper.writeValueAsString(event), PaymentFailedEvent.class);

        assertEquals(event, back);
        assertNull(back.failureReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "0.01", "9.99", "19.99", "59.97", "100.00", "1234.50", "999999999999999.99"})
    void moneyAmountSurvivesJsonRoundTripWithoutPrecisionLoss(String amount) throws Exception {
        BigDecimal original = new BigDecimal(amount);
        PaymentAuthorizedEvent event =
                new PaymentAuthorizedEvent(PAYMENT_ID, ORDER_ID, USER_ID, original, "USD");

        String json = mapper.writeValueAsString(event);
        PaymentAuthorizedEvent back = mapper.readValue(json, PaymentAuthorizedEvent.class);

        // amount is written as a bare JSON number in plain (non-exponential) form
        assertEquals("\"amount\":" + amount, extractAmountField(json));
        // value preserved exactly...
        assertEquals(0, original.compareTo(back.amount()), "value changed for " + amount);
        // ...and so is scale, so "100.00" does not come back as "100.0" or "1E+2"
        assertEquals(original, back.amount(), "scale/representation changed for " + amount);
    }

    private static String extractAmountField(String json) {
        int start = json.indexOf("\"amount\":");
        int end = json.indexOf(',', start);
        return json.substring(start, end);
    }
}
