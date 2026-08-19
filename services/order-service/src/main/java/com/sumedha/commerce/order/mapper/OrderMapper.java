package com.sumedha.commerce.order.mapper;

import com.sumedha.commerce.order.dto.response.OrderItemResponse;
import com.sumedha.commerce.order.dto.response.OrderResponse;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.entity.OrderItem;

import java.util.List;

public final class OrderMapper {

    private OrderMapper() {
    }

    public static OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(
                item.getId(),
                item.getProductId(),
                item.getProductName(),
                item.getSku(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getLineTotal(),
                item.getCreatedAt());
    }

    public static OrderResponse toResponse(Order order, List<OrderItem> items) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getSubtotal(),
                order.getTotal(),
                order.getCurrency(),
                items.stream().map(OrderMapper::toItemResponse).toList(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
