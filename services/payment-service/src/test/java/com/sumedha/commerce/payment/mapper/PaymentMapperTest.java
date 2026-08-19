package com.sumedha.commerce.payment.mapper;

import com.sumedha.commerce.payment.dto.response.PaymentResponse;
import com.sumedha.commerce.payment.entity.Payment;
import com.sumedha.commerce.payment.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PaymentMapperTest {

    @Test
    void mapsAllFields() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Payment payment = new Payment(orderId, userId, new BigDecimal("19.98"), "USD");
        payment.authorize("stripe", "ref-123");

        PaymentResponse response = PaymentMapper.toResponse(payment);

        assertEquals(payment.getId(), response.id());
        assertEquals(orderId, response.orderId());
        assertEquals(userId, response.userId());
        assertEquals(PaymentStatus.AUTHORIZED, response.status());
        assertEquals(new BigDecimal("19.98"), response.amount());
        assertEquals("USD", response.currency());
        assertEquals("stripe", response.provider());
        assertEquals("ref-123", response.providerReference());
        assertNull(response.failureReason());
        assertEquals(payment.getCreatedAt(), response.createdAt());
        assertEquals(payment.getUpdatedAt(), response.updatedAt());
    }

    @Test
    void doesNotExposeVersion() {
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "USD");

        PaymentResponse response = PaymentMapper.toResponse(payment);

        Set<String> fieldNames = Arrays.stream(response.getClass().getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        assertEquals(
                Set.of("id", "orderId", "userId", "status", "amount", "currency", "provider",
                        "providerReference", "failureReason", "createdAt", "updatedAt"),
                fieldNames);
    }
}
