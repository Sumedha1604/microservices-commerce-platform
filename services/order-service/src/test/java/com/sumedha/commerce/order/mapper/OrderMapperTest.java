package com.sumedha.commerce.order.mapper;

import com.sumedha.commerce.order.dto.response.OrderItemResponse;
import com.sumedha.commerce.order.dto.response.OrderResponse;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.entity.OrderItem;
import com.sumedha.commerce.order.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderMapperTest {

    @Test
    void mapsItemResponse() {
        OrderItem item = new OrderItem(UUID.randomUUID(), UUID.randomUUID(), "Widget", "SKU-1",
                new BigDecimal("9.99"), 2, new BigDecimal("19.98"));

        OrderItemResponse response = OrderMapper.toItemResponse(item);

        assertEquals(item.getId(), response.id());
        assertEquals(item.getProductId(), response.productId());
        assertEquals("Widget", response.productName());
        assertEquals("SKU-1", response.sku());
        assertEquals(new BigDecimal("9.99"), response.unitPrice());
        assertEquals(2, response.quantity());
        assertEquals(new BigDecimal("19.98"), response.lineTotal());
        assertEquals(item.getCreatedAt(), response.createdAt());
    }

    @Test
    void mapsOrderResponseWithItems() {
        UUID userId = UUID.randomUUID();
        Order order = new Order(userId, new BigDecimal("19.98"), new BigDecimal("19.98"), "USD");
        OrderItem item = new OrderItem(order.getId(), UUID.randomUUID(), "Widget", "SKU-1",
                new BigDecimal("9.99"), 2, new BigDecimal("19.98"));

        OrderResponse response = OrderMapper.toResponse(order, List.of(item));

        assertEquals(order.getId(), response.id());
        assertEquals(userId, response.userId());
        assertEquals(OrderStatus.PENDING, response.status());
        assertEquals(new BigDecimal("19.98"), response.subtotal());
        assertEquals(new BigDecimal("19.98"), response.total());
        assertEquals("USD", response.currency());
        assertEquals(1, response.items().size());
        assertEquals(item.getId(), response.items().get(0).id());
    }

    @Test
    void mapsOrderResponseWithEmptyItems() {
        Order order = new Order(UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.ZERO, "USD");

        OrderResponse response = OrderMapper.toResponse(order, List.of());

        assertTrue(response.items().isEmpty());
    }
}
