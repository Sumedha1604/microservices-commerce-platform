package com.sumedha.commerce.order.service;

import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.order.dto.request.CreateOrderItemRequest;
import com.sumedha.commerce.order.dto.request.CreateOrderRequest;
import com.sumedha.commerce.order.dto.response.OrderResponse;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.entity.OrderItem;
import com.sumedha.commerce.order.mapper.OrderMapper;
import com.sumedha.commerce.order.repository.OrderItemRepository;
import com.sumedha.commerce.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class OrderServiceImpl implements OrderService {

    private static final int MONEY_SCALE = 2;

    private final OrderRepository orders;
    private final OrderItemRepository items;

    public OrderServiceImpl(OrderRepository orders, OrderItemRepository items) {
        this.orders = orders;
        this.items = items;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        String currency = request.currency().toUpperCase();

        List<LineItemAmounts> amounts = request.items().stream().map(this::calculateAmounts).toList();
        BigDecimal subtotal = amounts.stream()
                .map(LineItemAmounts::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal total = subtotal;

        Order order = orders.save(new Order(request.userId(), subtotal, total, currency));

        List<CreateOrderItemRequest> itemRequests = request.items();
        List<OrderItem> savedItems = new ArrayList<>();
        for (int i = 0; i < itemRequests.size(); i++) {
            savedItems.add(items.save(toOrderItem(order.getId(), itemRequests.get(i), amounts.get(i))));
        }

        return OrderMapper.toResponse(order, savedItems);
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(UUID orderId) {
        Order order = order(orderId);
        return OrderMapper.toResponse(order, items.findByOrderId(order.getId()));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getByUserId(UUID userId) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(order -> OrderMapper.toResponse(order, items.findByOrderId(order.getId())))
                .toList();
    }

    @Transactional
    public OrderResponse confirm(UUID orderId) {
        Order order = order(orderId);
        order.confirm();
        return OrderMapper.toResponse(order, items.findByOrderId(order.getId()));
    }

    @Transactional
    public OrderResponse cancel(UUID orderId) {
        Order order = order(orderId);
        order.cancel();
        return OrderMapper.toResponse(order, items.findByOrderId(order.getId()));
    }

    private Order order(UUID id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    private OrderItem toOrderItem(UUID orderId, CreateOrderItemRequest request, LineItemAmounts amounts) {
        return new OrderItem(orderId, request.productId(), request.productName(), request.sku(),
                amounts.unitPrice(), request.quantity(), amounts.lineTotal());
    }

    private LineItemAmounts calculateAmounts(CreateOrderItemRequest request) {
        BigDecimal unitPrice = request.unitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal lineTotal = unitPrice
                .multiply(BigDecimal.valueOf(request.quantity()))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new LineItemAmounts(unitPrice, lineTotal);
    }

    private record LineItemAmounts(BigDecimal unitPrice, BigDecimal lineTotal) {
    }
}
