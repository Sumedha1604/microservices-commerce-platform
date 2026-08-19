package com.sumedha.commerce.order.service;

import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.order.dto.request.CreateOrderItemRequest;
import com.sumedha.commerce.order.dto.request.CreateOrderRequest;
import com.sumedha.commerce.order.dto.response.OrderItemResponse;
import com.sumedha.commerce.order.dto.response.OrderResponse;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.entity.OrderItem;
import com.sumedha.commerce.order.enums.OrderStatus;
import com.sumedha.commerce.order.repository.OrderItemRepository;
import com.sumedha.commerce.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    OrderRepository orders;

    @Mock
    OrderItemRepository items;

    OrderServiceImpl service;
    UUID orderId;
    UUID userId;
    UUID productId;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(orders, items);
        orderId = UUID.randomUUID();
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
    }

    private CreateOrderRequest requestWith(String currency, CreateOrderItemRequest... itemRequests) {
        return new CreateOrderRequest(userId, currency, List.of(itemRequests));
    }

    @Test
    void createsOrderWithPendingStatus() {
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(items.save(any(OrderItem.class))).thenAnswer(i -> i.getArgument(0));

        CreateOrderItemRequest itemRequest =
                new CreateOrderItemRequest(productId, "Widget", "SKU-1", new BigDecimal("10.00"), 2);
        OrderResponse response = service.create(requestWith("usd", itemRequest));

        assertEquals(OrderStatus.PENDING, response.status());
        verify(orders).save(any(Order.class));
    }

    @Test
    void calculatesLineTotalsCorrectly() {
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(items.save(any(OrderItem.class))).thenAnswer(i -> i.getArgument(0));

        CreateOrderItemRequest itemRequest =
                new CreateOrderItemRequest(productId, "Widget", "SKU-1", new BigDecimal("9.99"), 3);
        OrderResponse response = service.create(requestWith("USD", itemRequest));

        assertEquals(new BigDecimal("29.97"), response.items().get(0).lineTotal());
    }

    @Test
    void normalizesUnitPriceBeforeCalculatingLineTotalForMoreThanTwoDecimalPrices() {
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(items.save(any(OrderItem.class))).thenAnswer(i -> i.getArgument(0));

        CreateOrderItemRequest itemRequest =
                new CreateOrderItemRequest(productId, "Widget", "SKU-1", new BigDecimal("10.005"), 2);
        OrderResponse response = service.create(requestWith("USD", itemRequest));

        assertEquals(new BigDecimal("10.01"), response.items().get(0).unitPrice());
        assertEquals(new BigDecimal("20.02"), response.items().get(0).lineTotal());
        assertEquals(new BigDecimal("20.02"), response.subtotal());
        assertEquals(new BigDecimal("20.02"), response.total());
    }

    @Test
    void subtotalIsExactSumOfPersistedLineTotalsForMultipleMoreThanTwoDecimalPrices() {
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(items.save(any(OrderItem.class))).thenAnswer(i -> i.getArgument(0));

        CreateOrderItemRequest first =
                new CreateOrderItemRequest(productId, "Widget", "SKU-1", new BigDecimal("10.005"), 2);
        CreateOrderItemRequest second =
                new CreateOrderItemRequest(UUID.randomUUID(), "Gadget", "SKU-2", new BigDecimal("3.333"), 3);
        OrderResponse response = service.create(requestWith("USD", first, second));

        BigDecimal expectedSum = response.items().stream()
                .map(OrderItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(expectedSum, response.subtotal());
        assertEquals(new BigDecimal("20.02"), response.items().get(0).lineTotal());
        assertEquals(new BigDecimal("9.99"), response.items().get(1).lineTotal());
        assertEquals(new BigDecimal("30.01"), response.subtotal());
    }

    @Test
    void calculatesSubtotalAndTotalAsSumOfLineTotals() {
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(items.save(any(OrderItem.class))).thenAnswer(i -> i.getArgument(0));

        CreateOrderItemRequest first =
                new CreateOrderItemRequest(productId, "Widget", "SKU-1", new BigDecimal("10.00"), 2);
        CreateOrderItemRequest second =
                new CreateOrderItemRequest(UUID.randomUUID(), "Gadget", "SKU-2", new BigDecimal("5.50"), 1);
        OrderResponse response = service.create(requestWith("USD", first, second));

        assertEquals(new BigDecimal("25.50"), response.subtotal());
        assertEquals(new BigDecimal("25.50"), response.total());
    }

    @Test
    void normalizesCurrencyToUppercase() {
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(items.save(any(OrderItem.class))).thenAnswer(i -> i.getArgument(0));

        CreateOrderItemRequest itemRequest =
                new CreateOrderItemRequest(productId, "Widget", "SKU-1", new BigDecimal("10.00"), 1);
        OrderResponse response = service.create(requestWith("usd", itemRequest));

        assertEquals("USD", response.currency());
    }

    @Test
    void persistsOrderItems() {
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(items.save(any(OrderItem.class))).thenAnswer(i -> i.getArgument(0));

        CreateOrderItemRequest first =
                new CreateOrderItemRequest(productId, "Widget", "SKU-1", new BigDecimal("10.00"), 2);
        CreateOrderItemRequest second =
                new CreateOrderItemRequest(UUID.randomUUID(), "Gadget", "SKU-2", new BigDecimal("5.50"), 1);
        OrderResponse response = service.create(requestWith("USD", first, second));

        verify(items, org.mockito.Mockito.times(2)).save(any(OrderItem.class));
        assertEquals(2, response.items().size());
    }

    @Test
    void getByIdReturnsOrder() {
        Order order = new Order(userId, BigDecimal.TEN, BigDecimal.TEN, "USD");
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(items.findByOrderId(order.getId())).thenReturn(List.of());

        OrderResponse response = service.getById(order.getId());

        assertEquals(order.getId(), response.id());
        assertEquals(userId, response.userId());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(orders.findById(orderId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getById(orderId));
    }

    @Test
    void getByUserIdReturnsList() {
        Order order = new Order(userId, BigDecimal.TEN, BigDecimal.TEN, "USD");
        when(orders.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(order));
        when(items.findByOrderId(order.getId())).thenReturn(List.of());

        List<OrderResponse> response = service.getByUserId(userId);

        assertEquals(1, response.size());
        assertEquals(order.getId(), response.get(0).id());
    }

    @Test
    void getByUserIdReturnsEmptyListWhenNoneFound() {
        when(orders.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        List<OrderResponse> response = service.getByUserId(userId);

        assertTrue(response.isEmpty());
    }

    @Test
    void confirmTransitionsPendingToConfirmed() {
        Order order = new Order(userId, BigDecimal.TEN, BigDecimal.TEN, "USD");
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(items.findByOrderId(order.getId())).thenReturn(List.of());

        OrderResponse response = service.confirm(order.getId());

        assertEquals(OrderStatus.CONFIRMED, response.status());
    }

    @Test
    void confirmThrowsWhenMissing() {
        when(orders.findById(orderId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.confirm(orderId));
    }

    @Test
    void confirmRejectsAlreadyConfirmedOrder() {
        Order order = new Order(userId, BigDecimal.TEN, BigDecimal.TEN, "USD");
        order.confirm();
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));

        assertThrows(ConflictException.class, () -> service.confirm(order.getId()));
    }

    @Test
    void confirmRejectsCancelledOrder() {
        Order order = new Order(userId, BigDecimal.TEN, BigDecimal.TEN, "USD");
        order.cancel();
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));

        assertThrows(ConflictException.class, () -> service.confirm(order.getId()));
    }

    @Test
    void cancelTransitionsPendingToCancelled() {
        Order order = new Order(userId, BigDecimal.TEN, BigDecimal.TEN, "USD");
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(items.findByOrderId(order.getId())).thenReturn(List.of());

        OrderResponse response = service.cancel(order.getId());

        assertEquals(OrderStatus.CANCELLED, response.status());
    }

    @Test
    void cancelTransitionsConfirmedToCancelled() {
        Order order = new Order(userId, BigDecimal.TEN, BigDecimal.TEN, "USD");
        order.confirm();
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(items.findByOrderId(order.getId())).thenReturn(List.of());

        OrderResponse response = service.cancel(order.getId());

        assertEquals(OrderStatus.CANCELLED, response.status());
    }

    @Test
    void cancelRejectsAlreadyCancelledOrder() {
        Order order = new Order(userId, BigDecimal.TEN, BigDecimal.TEN, "USD");
        order.cancel();
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));

        assertThrows(ConflictException.class, () -> service.cancel(order.getId()));
    }

    @Test
    void cancelThrowsWhenMissing() {
        when(orders.findById(orderId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.cancel(orderId));
    }
}
