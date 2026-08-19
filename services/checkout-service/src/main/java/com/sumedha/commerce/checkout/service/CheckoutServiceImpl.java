package com.sumedha.commerce.checkout.service;

import com.sumedha.commerce.checkout.client.CartClient;
import com.sumedha.commerce.checkout.client.InventoryClient;
import com.sumedha.commerce.checkout.client.OrderClient;
import com.sumedha.commerce.checkout.client.PaymentClient;
import com.sumedha.commerce.checkout.client.ProductClient;
import com.sumedha.commerce.checkout.dto.downstream.cart.CartGetResponse;
import com.sumedha.commerce.checkout.dto.downstream.cart.CartItemResponse;
import com.sumedha.commerce.checkout.dto.downstream.inventory.InventoryGetResponse;
import com.sumedha.commerce.checkout.dto.downstream.inventory.ReserveReleaseInventoryRequest;
import com.sumedha.commerce.checkout.dto.downstream.order.CreateOrderItemRequest;
import com.sumedha.commerce.checkout.dto.downstream.order.CreateOrderRequest;
import com.sumedha.commerce.checkout.dto.downstream.order.OrderResponse;
import com.sumedha.commerce.checkout.dto.downstream.payment.CreatePaymentRequest;
import com.sumedha.commerce.checkout.dto.downstream.payment.PaymentResponse;
import com.sumedha.commerce.checkout.dto.downstream.product.ProductGetResponse;
import com.sumedha.commerce.checkout.dto.response.CheckoutResponse;
import com.sumedha.commerce.common.core.exception.BadRequestException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class CheckoutServiceImpl implements CheckoutService {

    private final CartClient cartClient;
    private final ProductClient productClient;
    private final InventoryClient inventoryClient;
    private final OrderClient orderClient;
    private final PaymentClient paymentClient;

    public CheckoutServiceImpl(
            CartClient cartClient,
            ProductClient productClient,
            InventoryClient inventoryClient,
            OrderClient orderClient,
            PaymentClient paymentClient) {
        this.cartClient = cartClient;
        this.productClient = productClient;
        this.inventoryClient = inventoryClient;
        this.orderClient = orderClient;
        this.paymentClient = paymentClient;
    }

    @Override
    public CheckoutResponse checkout(UUID cartId) {
        CartGetResponse cart = cartClient.getCart(cartId);
        if (cart.items() == null || cart.items().isEmpty()) {
            throw new BadRequestException("Cart must contain at least one item");
        }

        List<ProductGetResponse> products = cart.items().stream()
                .map(item -> productClient.getProduct(item.productId()))
                .toList();
        products.forEach(this::validateProduct);
        String currency = validateSingleCurrency(products);
        List<Reservation> reservations = reserveInventory(cart.items());
        OrderResponse order;
        try {
            order = orderClient.createOrder(buildOrderRequest(cart, products, currency));
        } catch (RuntimeException exception) {
            releaseReservations(reservations);
            throw exception;
        }

        try {
            PaymentResponse payment = paymentClient.createPayment(
                    new CreatePaymentRequest(order.id(), cart.userId(), order.total(), order.currency()));
            return new CheckoutResponse(
                    cart.id(), order.id(), payment.id(), order.status(), payment.status(), order.total(), order.currency());
        } catch (RuntimeException exception) {
            cancelOrder(order.id());
            releaseReservations(reservations);
            throw exception;
        }
    }

    private void validateProduct(ProductGetResponse product) {
        if (product == null || !"ACTIVE".equals(product.status()) || !product.active()) {
            throw new BadRequestException("Cart contains an unavailable product");
        }
    }

    private String validateSingleCurrency(List<ProductGetResponse> products) {
        String currency = products.getFirst().currency();
        if (currency == null || products.stream().anyMatch(product -> !currency.equals(product.currency()))) {
            throw new BadRequestException("All cart products must use the same currency");
        }
        return currency;
    }

    private List<Reservation> reserveInventory(List<CartItemResponse> items) {
        List<Reservation> reservations = new java.util.ArrayList<>();
        try {
            for (CartItemResponse item : items) {
                InventoryGetResponse inventory = inventoryClient.getInventoryByProductId(item.productId());
                if (inventory.availableQuantity() < item.quantity()) {
                    throw new BadRequestException("Insufficient inventory for product " + item.productId());
                }

                ReserveReleaseInventoryRequest request = new ReserveReleaseInventoryRequest(item.quantity());
                inventoryClient.reserve(inventory.id(), request);
                reservations.add(new Reservation(inventory.id(), request));
            }
        } catch (RuntimeException exception) {
            releaseReservations(reservations);
            throw exception;
        }
        return reservations;
    }

    private CreateOrderRequest buildOrderRequest(
            CartGetResponse cart,
            List<ProductGetResponse> products,
            String currency) {
        List<CreateOrderItemRequest> items = new java.util.ArrayList<>();
        for (int index = 0; index < cart.items().size(); index++) {
            CartItemResponse cartItem = cart.items().get(index);
            ProductGetResponse product = products.get(index);
            items.add(new CreateOrderItemRequest(
                    product.productId(), product.name(), product.sku(), product.price(), cartItem.quantity()));
        }
        return new CreateOrderRequest(cart.userId(), currency, items);
    }

    private void releaseReservations(List<Reservation> reservations) {
        for (Reservation reservation : reservations) {
            try {
                inventoryClient.release(reservation.inventoryId(), reservation.request());
            } catch (RuntimeException ignored) {
                // Compensation is best-effort; the original checkout failure must be preserved.
            }
        }
    }

    private void cancelOrder(UUID orderId) {
        try {
            orderClient.cancelOrder(orderId);
        } catch (RuntimeException ignored) {
            // Compensation is best-effort; the original checkout failure must be preserved.
        }
    }

    private record Reservation(UUID inventoryId, ReserveReleaseInventoryRequest request) {
    }
}
