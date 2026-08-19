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
import com.sumedha.commerce.checkout.dto.downstream.order.CreateOrderRequest;
import com.sumedha.commerce.checkout.dto.downstream.order.OrderResponse;
import com.sumedha.commerce.checkout.dto.downstream.payment.CreatePaymentRequest;
import com.sumedha.commerce.checkout.dto.downstream.payment.PaymentResponse;
import com.sumedha.commerce.checkout.dto.downstream.product.ProductGetResponse;
import com.sumedha.commerce.checkout.dto.response.CheckoutResponse;
import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.InternalServerException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckoutServiceImplTest {

    @Test
    void createsPaymentAfterCreatingOrder() {
        UUID cartId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        StubInventoryClient inventoryClient = inventoryClient(firstProductId, 2, secondProductId, 3);
        StubProductClient productClient = activeProducts(firstProductId, "USD", secondProductId, "USD");
        StubOrderClient orderClient = new StubOrderClient();
        StubPaymentClient paymentClient = new StubPaymentClient();
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, firstProductId, secondProductId)), productClient, inventoryClient,
                orderClient, paymentClient);

        assertDoesNotThrow(() -> service.checkout(cartId));

        assertEquals(1, productClient.callsById.get(firstProductId));
        assertEquals(1, productClient.callsById.get(secondProductId));
        assertEquals(2, inventoryClient.reservations.size());
        assertEquals(0, inventoryClient.releases.size());
        assertEquals(1, orderClient.requests.size());
        assertEquals(1, paymentClient.requests.size());
    }

    @Test
    void returnsCheckoutResponseWithOrderAndPaymentFields() {
        UUID cartId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        OrderResponse order = new OrderResponse(
                orderId, userId, "PENDING", new BigDecimal("20.00"), new BigDecimal("24.50"), "USD",
                List.of(), Instant.now(), Instant.now());
        PaymentResponse payment = new PaymentResponse(
                paymentId, orderId, userId, "AUTHORIZED", new BigDecimal("24.50"), "USD",
                "provider", "reference", null, Instant.now(), Instant.now());
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, userId, productId, 1)),
                activeProducts(productId, "USD"), inventoryClient(productId, 1),
                new StubOrderClient(order), new StubPaymentClient(payment));

        CheckoutResponse response = service.checkout(cartId);

        assertEquals(cartId, response.cartId());
        assertEquals(orderId, response.orderId());
        assertEquals(paymentId, response.paymentId());
        assertEquals("PENDING", response.orderStatus());
        assertEquals("AUTHORIZED", response.paymentStatus());
        assertEquals(new BigDecimal("24.50"), response.total());
        assertEquals("USD", response.currency());
    }

    @Test
    void paymentFailureCancelsOrderAndReleasesInventory() {
        UUID cartId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        StubInventoryClient inventoryClient = inventoryClient(firstProductId, 1, secondProductId, 1);
        StubOrderClient orderClient = new StubOrderClient();
        InternalServerException originalFailure = new InternalServerException("Payment service failed");
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, firstProductId, secondProductId)),
                activeProducts(firstProductId, "USD", secondProductId, "USD"), inventoryClient, orderClient,
                new StubPaymentClient(originalFailure));

        InternalServerException thrown = assertThrows(InternalServerException.class, () -> service.checkout(cartId));

        assertSame(originalFailure, thrown);
        assertEquals(1, orderClient.cancelledOrderIds.size());
        assertEquals(2, inventoryClient.releases.size());
    }

    @Test
    void paymentCompensationFailureDoesNotReplaceOriginalError() {
        UUID cartId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        StubInventoryClient inventoryClient = inventoryClient(firstProductId, 1, secondProductId, 1);
        inventoryClient.releaseFailures.put(
                inventoryClient.inventoryByProductId.get(firstProductId).id(), new InternalServerException("Release failed"));
        InternalServerException originalFailure = new InternalServerException("Payment service failed");
        StubOrderClient orderClient = new StubOrderClient();
        orderClient.cancelFailure = new InternalServerException("Cancel failed");
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, firstProductId, secondProductId)),
                activeProducts(firstProductId, "USD", secondProductId, "USD"), inventoryClient, orderClient,
                new StubPaymentClient(originalFailure));

        InternalServerException thrown = assertThrows(InternalServerException.class, () -> service.checkout(cartId));

        assertSame(originalFailure, thrown);
        assertEquals(1, orderClient.cancelledOrderIds.size());
        assertEquals(2, inventoryClient.releases.size());
    }

    @Test
    void buildsOrderPayloadFromAuthoritativeProductData() {
        UUID cartId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        ProductGetResponse product = new ProductGetResponse(
                productId, "LIVE-SKU", "Live product name", new BigDecimal("24.50"), "USD", "ACTIVE", true);
        StubOrderClient orderClient = new StubOrderClient();
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, userId, productId, 3)),
                new StubProductClient(Map.of(productId, product)), inventoryClient(productId, 3), orderClient,
                new StubPaymentClient());

        service.checkout(cartId);

        CreateOrderRequest request = orderClient.requests.getFirst();
        assertEquals(userId, request.userId());
        assertEquals("USD", request.currency());
        assertEquals(1, request.items().size());
        assertEquals(productId, request.items().getFirst().productId());
        assertEquals("Live product name", request.items().getFirst().productName());
        assertEquals("LIVE-SKU", request.items().getFirst().sku());
        assertEquals(new BigDecimal("24.50"), request.items().getFirst().unitPrice());
        assertEquals(3, request.items().getFirst().quantity());
    }

    @Test
    void orderFailureReleasesAllReservations() {
        UUID cartId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        StubInventoryClient inventoryClient = inventoryClient(firstProductId, 1, secondProductId, 1);
        InternalServerException originalFailure = new InternalServerException("Order service failed");
        StubOrderClient orderClient = new StubOrderClient(originalFailure);
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, firstProductId, secondProductId)),
                activeProducts(firstProductId, "USD", secondProductId, "USD"), inventoryClient, orderClient,
                new StubPaymentClient());

        InternalServerException thrown = assertThrows(InternalServerException.class, () -> service.checkout(cartId));

        assertSame(originalFailure, thrown);
        assertEquals(2, inventoryClient.releases.size());
    }

    @Test
    void releaseFailureDoesNotReplaceTheOriginalOrderFailure() {
        UUID cartId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        StubInventoryClient inventoryClient = inventoryClient(firstProductId, 1, secondProductId, 1);
        inventoryClient.releaseFailures.put(
                inventoryClient.inventoryByProductId.get(firstProductId).id(), new InternalServerException("Release failed"));
        InternalServerException originalFailure = new InternalServerException("Order service failed");
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, firstProductId, secondProductId)),
                activeProducts(firstProductId, "USD", secondProductId, "USD"), inventoryClient,
                new StubOrderClient(originalFailure), new StubPaymentClient());

        InternalServerException thrown = assertThrows(InternalServerException.class, () -> service.checkout(cartId));

        assertSame(originalFailure, thrown);
        assertEquals(2, inventoryClient.releases.size());
    }

    @Test
    void rejectsAnEmptyCart() {
        UUID cartId = UUID.randomUUID();
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId)), new StubProductClient(Map.of()), new StubInventoryClient(Map.of()),
                new StubOrderClient(), new StubPaymentClient());

        assertThrows(BadRequestException.class, () -> service.checkout(cartId));
    }

    @Test
    void propagatesMissingProductFailure() {
        UUID cartId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, productId)),
                new StubProductClient(Map.of(productId, new ResourceNotFoundException("Product not found"))),
                new StubInventoryClient(Map.of()), new StubOrderClient(), new StubPaymentClient());

        assertThrows(ResourceNotFoundException.class, () -> service.checkout(cartId));
    }

    @Test
    void rejectsInactiveProduct() {
        UUID cartId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, productId)),
                new StubProductClient(Map.of(productId,
                        new ProductGetResponse(productId, "SKU-1", "Product", BigDecimal.TEN, "USD", "INACTIVE", false))),
                new StubInventoryClient(Map.of()), new StubOrderClient(), new StubPaymentClient());

        assertThrows(BadRequestException.class, () -> service.checkout(cartId));
    }

    @Test
    void rejectsMixedCurrencies() {
        UUID cartId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, firstProductId, secondProductId)),
                activeProducts(firstProductId, "USD", secondProductId, "EUR"), new StubInventoryClient(Map.of()),
                new StubOrderClient(), new StubPaymentClient());

        assertThrows(BadRequestException.class, () -> service.checkout(cartId));
    }

    @Test
    void rejectsInsufficientInventoryBeforeReservingIt() {
        UUID cartId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        StubInventoryClient inventoryClient = inventoryClient(productId, 0);
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, productId)), activeProducts(productId, "USD"), inventoryClient,
                new StubOrderClient(), new StubPaymentClient());

        assertThrows(BadRequestException.class, () -> service.checkout(cartId));

        assertEquals(0, inventoryClient.reservations.size());
    }

    @Test
    void releasesEarlierReservationWhenSecondReservationFails() {
        UUID cartId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        StubInventoryClient inventoryClient = inventoryClient(firstProductId, 1, secondProductId, 1);
        UUID secondInventoryId = inventoryClient.inventoryByProductId.get(secondProductId).id();
        InternalServerException originalFailure = new InternalServerException("Inventory reserve failed");
        inventoryClient.reserveFailures.put(secondInventoryId, originalFailure);
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, firstProductId, secondProductId)),
                activeProducts(firstProductId, "USD", secondProductId, "USD"), inventoryClient, new StubOrderClient(),
                new StubPaymentClient());

        InternalServerException thrown = assertThrows(InternalServerException.class, () -> service.checkout(cartId));

        assertSame(originalFailure, thrown);
        assertEquals(1, inventoryClient.releases.size());
        assertEquals(inventoryClient.inventoryByProductId.get(firstProductId).id(), inventoryClient.releases.getFirst().inventoryId());
    }

    @Test
    void releaseFailureDoesNotReplaceTheOriginalReservationFailure() {
        UUID cartId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        StubInventoryClient inventoryClient = inventoryClient(firstProductId, 1, secondProductId, 1);
        UUID firstInventoryId = inventoryClient.inventoryByProductId.get(firstProductId).id();
        UUID secondInventoryId = inventoryClient.inventoryByProductId.get(secondProductId).id();
        InternalServerException originalFailure = new InternalServerException("Second reservation failed");
        inventoryClient.reserveFailures.put(secondInventoryId, originalFailure);
        inventoryClient.releaseFailures.put(firstInventoryId, new InternalServerException("Release failed"));
        CheckoutService service = new CheckoutServiceImpl(
                new StubCartClient(cartWith(cartId, firstProductId, secondProductId)),
                activeProducts(firstProductId, "USD", secondProductId, "USD"), inventoryClient, new StubOrderClient(),
                new StubPaymentClient());

        InternalServerException thrown = assertThrows(InternalServerException.class, () -> service.checkout(cartId));

        assertSame(originalFailure, thrown);
        assertEquals(1, inventoryClient.releases.size());
    }

    private static CartGetResponse cartWith(UUID cartId, UUID... productIds) {
        List<CartItemResponse> items = java.util.Arrays.stream(productIds)
                .map(productId -> new CartItemResponse(UUID.randomUUID(), productId, 1, Instant.now(), Instant.now()))
                .toList();
        return new CartGetResponse(cartId, UUID.randomUUID(), items, Instant.now(), Instant.now());
    }

    private static CartGetResponse cartWith(UUID cartId, UUID userId, UUID productId, int quantity) {
        CartItemResponse item = new CartItemResponse(UUID.randomUUID(), productId, quantity, Instant.now(), Instant.now());
        return new CartGetResponse(cartId, userId, List.of(item), Instant.now(), Instant.now());
    }

    private static StubProductClient activeProducts(Object... productIdAndCurrency) {
        Map<UUID, ProductGetResponse> products = new HashMap<>();
        for (int index = 0; index < productIdAndCurrency.length; index += 2) {
            UUID productId = (UUID) productIdAndCurrency[index];
            products.put(productId, activeProduct(productId, (String) productIdAndCurrency[index + 1]));
        }
        return new StubProductClient(products);
    }

    private static ProductGetResponse activeProduct(UUID productId, String currency) {
        return new ProductGetResponse(productId, "SKU-" + productId, "Product", BigDecimal.TEN, currency, "ACTIVE", true);
    }

    private static StubInventoryClient inventoryClient(Object... productIdAndAvailableQuantity) {
        Map<UUID, InventoryGetResponse> inventory = new HashMap<>();
        for (int index = 0; index < productIdAndAvailableQuantity.length; index += 2) {
            UUID productId = (UUID) productIdAndAvailableQuantity[index];
            int availableQuantity = (int) productIdAndAvailableQuantity[index + 1];
            inventory.put(productId, new InventoryGetResponse(
                    UUID.randomUUID(), productId, availableQuantity, 0, availableQuantity, Instant.now(), Instant.now()));
        }
        return new StubInventoryClient(inventory);
    }

    private static final class StubCartClient extends CartClient {
        private final CartGetResponse cart;

        private StubCartClient(CartGetResponse cart) {
            super("http://localhost");
            this.cart = cart;
        }

        @Override
        public CartGetResponse getCart(UUID cartId) {
            return cart;
        }
    }

    private static final class StubProductClient extends ProductClient {
        private final Map<UUID, ?> responses;
        private final Map<UUID, Integer> callsById = new HashMap<>();

        private StubProductClient(Map<UUID, ?> responses) {
            super("http://localhost");
            this.responses = new HashMap<>(responses);
        }

        @Override
        public ProductGetResponse getProduct(UUID productId) {
            callsById.merge(productId, 1, Integer::sum);
            Object response = responses.get(productId);
            if (response instanceof RuntimeException exception) {
                throw exception;
            }
            return (ProductGetResponse) response;
        }
    }

    private static final class StubInventoryClient extends InventoryClient {
        private final Map<UUID, InventoryGetResponse> inventoryByProductId;
        private final Map<UUID, RuntimeException> reserveFailures = new HashMap<>();
        private final Map<UUID, RuntimeException> releaseFailures = new HashMap<>();
        private final List<ReservationCall> reservations = new ArrayList<>();
        private final List<ReservationCall> releases = new ArrayList<>();

        private StubInventoryClient(Map<UUID, InventoryGetResponse> inventoryByProductId) {
            super("http://localhost");
            this.inventoryByProductId = new HashMap<>(inventoryByProductId);
        }

        @Override
        public InventoryGetResponse getInventoryByProductId(UUID productId) {
            return inventoryByProductId.get(productId);
        }

        @Override
        public InventoryGetResponse reserve(UUID inventoryId, ReserveReleaseInventoryRequest request) {
            reservations.add(new ReservationCall(inventoryId, request));
            RuntimeException failure = reserveFailures.get(inventoryId);
            if (failure != null) {
                throw failure;
            }
            return findInventory(inventoryId);
        }

        @Override
        public InventoryGetResponse release(UUID inventoryId, ReserveReleaseInventoryRequest request) {
            releases.add(new ReservationCall(inventoryId, request));
            RuntimeException failure = releaseFailures.get(inventoryId);
            if (failure != null) {
                throw failure;
            }
            return findInventory(inventoryId);
        }

        private InventoryGetResponse findInventory(UUID inventoryId) {
            return inventoryByProductId.values().stream()
                    .filter(inventory -> inventory.id().equals(inventoryId))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static final class StubOrderClient extends OrderClient {
        private final RuntimeException failure;
        private final List<CreateOrderRequest> requests = new ArrayList<>();
        private final List<UUID> cancelledOrderIds = new ArrayList<>();
        private final OrderResponse response;
        private RuntimeException cancelFailure;

        private StubOrderClient() {
            this(null, null);
        }

        private StubOrderClient(RuntimeException failure) {
            this(failure, null);
        }

        private StubOrderClient(OrderResponse response) {
            this(null, response);
        }

        private StubOrderClient(RuntimeException failure, OrderResponse response) {
            super("http://localhost");
            this.failure = failure;
            this.response = response;
        }

        @Override
        public OrderResponse createOrder(CreateOrderRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            if (response != null) {
                return response;
            }
            return new OrderResponse(
                    UUID.randomUUID(), request.userId(), "PENDING", BigDecimal.ZERO, BigDecimal.ZERO,
                    request.currency(), List.of(), Instant.now(), Instant.now());
        }

        @Override
        public OrderResponse cancelOrder(UUID orderId) {
            cancelledOrderIds.add(orderId);
            if (cancelFailure != null) {
                throw cancelFailure;
            }
            return new OrderResponse(
                    orderId, UUID.randomUUID(), "CANCELLED", BigDecimal.ZERO, BigDecimal.ZERO,
                    "USD", List.of(), Instant.now(), Instant.now());
        }
    }

    private static final class StubPaymentClient extends PaymentClient {
        private final RuntimeException failure;
        private final PaymentResponse response;
        private final List<CreatePaymentRequest> requests = new ArrayList<>();

        private StubPaymentClient() {
            this(null, null);
        }

        private StubPaymentClient(RuntimeException failure) {
            this(failure, null);
        }

        private StubPaymentClient(PaymentResponse response) {
            this(null, response);
        }

        private StubPaymentClient(RuntimeException failure, PaymentResponse response) {
            super("http://localhost");
            this.failure = failure;
            this.response = response;
        }

        @Override
        public PaymentResponse createPayment(CreatePaymentRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            if (response != null) {
                return response;
            }
            return new PaymentResponse(
                    UUID.randomUUID(), request.orderId(), request.userId(), "PENDING", request.amount(), request.currency(),
                    "provider", "reference", null, Instant.now(), Instant.now());
        }
    }

    private record ReservationCall(UUID inventoryId, ReserveReleaseInventoryRequest request) {
    }
}
