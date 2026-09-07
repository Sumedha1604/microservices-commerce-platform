package com.sumedha.commerce.payment.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level proof of the wire contract and the after-commit failure semantics, with a
 * mocked {@link KafkaTemplate} (no broker).
 */
class PaymentEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final PaymentEventPublisher publisher = new PaymentEventPublisher(kafkaTemplate);

    private final UUID paymentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID userId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private ListAppender<ILoggingEvent> logs;
    private Logger publisherLogger;

    @BeforeEach
    void attachLogAppender() {
        publisherLogger = (Logger) LoggerFactory.getLogger(PaymentEventPublisher.class);
        logs = new ListAppender<>();
        logs.start();
        publisherLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        publisherLogger.detachAppender(logs);
    }

    @SuppressWarnings("unchecked")
    private void stubSuccessfulSend() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private String captureSentValue(String expectedKey) {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.PAYMENT_EVENTS_V1), eq(expectedKey), value.capture());
        return value.getValue();
    }

    // ---- A / G : PaymentAuthorized wire contract ----

    @Test
    void publishesPaymentAuthorizedToTheV1TopicKeyedByOrderId() {
        stubSuccessfulSend();

        publisher.publishPaymentAuthorized(
                new PaymentAuthorizedEvent(paymentId, orderId, userId, new BigDecimal("59.97"), "USD"));

        String value = captureSentValue(orderId.toString());
        EventEnvelope<PaymentAuthorizedEvent> envelope =
                json.readValue(value, new TypeReference<EventEnvelope<PaymentAuthorizedEvent>>() {});

        assertEquals(EventTypes.PAYMENT_AUTHORIZED, envelope.eventType());
        assertEquals(1, envelope.schemaVersion());
        assertNotNull(envelope.eventId());
        assertNotNull(envelope.occurredAt());
        assertEquals(paymentId, envelope.payload().paymentId());
        assertEquals(orderId, envelope.payload().orderId());
        assertEquals(userId, envelope.payload().userId());
        assertEquals(0, new BigDecimal("59.97").compareTo(envelope.payload().amount()));
        assertEquals("USD", envelope.payload().currency());
    }

    // ---- B : PaymentFailed wire contract ----

    @Test
    void publishesPaymentFailedWithFailureReasonPreserved() {
        stubSuccessfulSend();

        publisher.publishPaymentFailed(
                new PaymentFailedEvent(paymentId, orderId, userId, "card declined by issuer"));

        String value = captureSentValue(orderId.toString());
        EventEnvelope<PaymentFailedEvent> envelope =
                json.readValue(value, new TypeReference<EventEnvelope<PaymentFailedEvent>>() {});

        assertEquals(EventTypes.PAYMENT_FAILED, envelope.eventType());
        assertEquals(1, envelope.schemaVersion());
        assertEquals(orderId, envelope.payload().orderId());
        assertEquals("card declined by issuer", envelope.payload().failureReason());
    }

    // ---- Java type metadata absent ----

    @Test
    @SuppressWarnings("unchecked")
    void serializedValueIsTheExplicitEnvelopeJsonWithNoJavaTypeMetadata() {
        stubSuccessfulSend();

        publisher.publishPaymentAuthorized(
                new PaymentAuthorizedEvent(paymentId, orderId, userId, new BigDecimal("10.00"), "USD"));

        String value = captureSentValue(orderId.toString());
        assertTrue(value.startsWith("{"));
        assertFalse(value.contains("@class"), () -> "unexpected @class in " + value);
        assertFalse(value.contains("@type"), () -> "unexpected @type in " + value);
        assertFalse(value.contains("__TypeId__"), () -> "unexpected type header field in " + value);

        Map<String, Object> root = json.readValue(value, Map.class);
        assertEquals(java.util.Set.of("eventId", "eventType", "schemaVersion", "occurredAt", "payload"),
                root.keySet());
        Map<String, Object> payload = (Map<String, Object>) root.get("payload");
        assertEquals(java.util.Set.of("paymentId", "orderId", "userId", "amount", "currency"),
                payload.keySet());
    }

    // ---- F : after-commit publication failure is logged, never rethrown ----

    @Test
    void asyncSendFailureIsLoggedAndNotRethrown() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        publisher.publishPaymentAuthorized(
                new PaymentAuthorizedEvent(paymentId, orderId, userId, new BigDecimal("10.00"), "USD"));

        assertTrue(errorLogged("failed AFTER the payment transaction committed"),
                () -> "expected a post-commit publish-failure ERROR, got: " + renderedLogs());
    }

    @Test
    void synchronousSendRejectionIsLoggedAndNotRethrown() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new KafkaException("producer fenced"));

        publisher.publishPaymentFailed(new PaymentFailedEvent(paymentId, orderId, userId, "x"));

        assertTrue(errorLogged("rejected the"),
                () -> "expected a synchronous send-rejection ERROR, got: " + renderedLogs());
    }

    private boolean errorLogged(String fragment) {
        return logs.list.stream()
                .anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains(fragment));
    }

    private String renderedLogs() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString();
    }
}
