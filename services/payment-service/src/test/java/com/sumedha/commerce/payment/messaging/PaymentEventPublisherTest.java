package com.sumedha.commerce.payment.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentEventPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void delegatesAlreadySerializedOutboxRecordAndReturnsKafkaAcknowledgementFuture() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> acknowledgement = new CompletableFuture<>();
        when(template.send("payment.events.v1", "order-1", "{\"eventId\":\"one\"}"))
                .thenReturn(acknowledgement);

        PaymentEventPublisher publisher = new PaymentEventPublisher(template);

        assertSame(acknowledgement,
                publisher.publish("payment.events.v1", "order-1", "{\"eventId\":\"one\"}"));
        verify(template).send("payment.events.v1", "order-1", "{\"eventId\":\"one\"}");
    }
}
