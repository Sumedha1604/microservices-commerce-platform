package com.sumedha.commerce.order.service;

import com.sumedha.commerce.order.dto.request.CreateOrderRequest;
import com.sumedha.commerce.order.dto.response.OrderResponse;

import java.util.List;
import java.util.UUID;

public interface OrderService {

    OrderResponse create(CreateOrderRequest request);

    OrderResponse getById(UUID orderId);

    List<OrderResponse> getByUserId(UUID userId);

    OrderResponse confirm(UUID orderId);

    OrderResponse cancel(UUID orderId);
}
