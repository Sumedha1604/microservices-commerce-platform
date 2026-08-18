package com.sumedha.commerce.cart.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sumedha.commerce.cart.dto.request.AddCartItemRequest;
import com.sumedha.commerce.cart.dto.request.CreateCartRequest;
import com.sumedha.commerce.cart.dto.request.UpdateCartItemQuantityRequest;
import com.sumedha.commerce.cart.dto.response.CartItemResponse;
import com.sumedha.commerce.cart.dto.response.CartResponse;
import com.sumedha.commerce.cart.exception.GlobalExceptionHandler;
import com.sumedha.commerce.cart.service.CartService;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CartControllerTest {

    MockMvc mvc;
    CartService service;
    ObjectMapper mapper = new ObjectMapper();
    UUID cartId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(CartService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CartController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private CartResponse cartResponse(CartItemResponse... items) {
        Instant now = Instant.now();
        return new CartResponse(cartId, userId, List.of(items), now, now);
    }

    private CartItemResponse itemResponse(int quantity) {
        Instant now = Instant.now();
        return new CartItemResponse(UUID.randomUUID(), productId, quantity, now, now);
    }

    // ---------- create cart ----------

    @Test
    void createCartReturnsCreatedWithBody() throws Exception {
        when(service.createCart(any())).thenReturn(cartResponse());

        CreateCartRequest request = new CreateCartRequest(userId);
        mvc.perform(post("/api/v1/carts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(cartId.toString()))
                .andExpect(jsonPath("$.data.userId").value(userId.toString()))
                .andExpect(jsonPath("$.data.items").isArray());

        verify(service).createCart(any());
    }

    @Test
    void createCartRejectsNullUserId() throws Exception {
        CreateCartRequest request = new CreateCartRequest(null);

        mvc.perform(post("/api/v1/carts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createCartMapsConflictExceptionWhenCartAlreadyExists() throws Exception {
        when(service.createCart(any())).thenThrow(new ConflictException("Cart already exists for user"));

        CreateCartRequest request = new CreateCartRequest(userId);
        mvc.perform(post("/api/v1/carts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Cart already exists for user"));
    }

    @Test
    void createCartRejectsMalformedJsonBody() throws Exception {
        mvc.perform(post("/api/v1/carts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createCartMapsUnexpectedErrorToSanitizedResponse() throws Exception {
        when(service.createCart(any())).thenThrow(new RuntimeException("db connection string leaked here"));

        CreateCartRequest request = new CreateCartRequest(userId);
        mvc.perform(post("/api/v1/carts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    // ---------- get by cart id ----------

    @Test
    void getCartByIdReturnsCart() throws Exception {
        when(service.getCartById(cartId)).thenReturn(cartResponse(itemResponse(2)));

        mvc.perform(get("/api/v1/carts/{cartId}", cartId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(cartId.toString()))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));
    }

    @Test
    void getCartByIdMapsResourceNotFound() throws Exception {
        when(service.getCartById(cartId)).thenThrow(new ResourceNotFoundException("Cart not found"));

        mvc.perform(get("/api/v1/carts/{cartId}", cartId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Cart not found"));
    }

    @Test
    void getCartByIdWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/carts/{cartId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- get by user id ----------

    @Test
    void getCartByUserIdReturnsCart() throws Exception {
        when(service.getCartByUserId(userId)).thenReturn(cartResponse());

        mvc.perform(get("/api/v1/carts/user/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(userId.toString()));
    }

    @Test
    void getCartByUserIdMapsResourceNotFound() throws Exception {
        when(service.getCartByUserId(userId)).thenThrow(new ResourceNotFoundException("Cart not found"));

        mvc.perform(get("/api/v1/carts/user/{userId}", userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void getCartByUserIdWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/carts/user/{userId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- add item ----------

    @Test
    void addItemReturnsUpdatedCart() throws Exception {
        when(service.addItem(eq(cartId), any())).thenReturn(cartResponse(itemResponse(3)));

        AddCartItemRequest request = new AddCartItemRequest(productId, 3);
        mvc.perform(post("/api/v1/carts/{cartId}/items", cartId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].quantity").value(3));

        verify(service).addItem(eq(cartId), any());
    }

    @Test
    void addItemRejectsNullProductId() throws Exception {
        AddCartItemRequest request = new AddCartItemRequest(null, 2);

        mvc.perform(post("/api/v1/carts/{cartId}/items", cartId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void addItemRejectsZeroQuantity() throws Exception {
        AddCartItemRequest request = new AddCartItemRequest(productId, 0);

        mvc.perform(post("/api/v1/carts/{cartId}/items", cartId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void addItemRejectsNegativeQuantity() throws Exception {
        AddCartItemRequest request = new AddCartItemRequest(productId, -1);

        mvc.perform(post("/api/v1/carts/{cartId}/items", cartId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void addItemMapsResourceNotFoundWhenCartMissing() throws Exception {
        when(service.addItem(eq(cartId), any())).thenThrow(new ResourceNotFoundException("Cart not found"));

        AddCartItemRequest request = new AddCartItemRequest(productId, 1);
        mvc.perform(post("/api/v1/carts/{cartId}/items", cartId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void addItemWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        AddCartItemRequest request = new AddCartItemRequest(productId, 1);
        mvc.perform(post("/api/v1/carts/{cartId}/items", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void addItemRejectsMalformedJsonBody() throws Exception {
        mvc.perform(post("/api/v1/carts/{cartId}/items", cartId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- update item quantity ----------

    @Test
    void updateItemQuantityReturnsUpdatedCart() throws Exception {
        when(service.updateItemQuantity(eq(cartId), eq(productId), any())).thenReturn(cartResponse(itemResponse(5)));

        UpdateCartItemQuantityRequest request = new UpdateCartItemQuantityRequest(5);
        mvc.perform(patch("/api/v1/carts/{cartId}/items/{productId}", cartId, productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].quantity").value(5));

        verify(service).updateItemQuantity(eq(cartId), eq(productId), any());
    }

    @Test
    void updateItemQuantityRejectsInvalidQuantity() throws Exception {
        UpdateCartItemQuantityRequest request = new UpdateCartItemQuantityRequest(0);

        mvc.perform(patch("/api/v1/carts/{cartId}/items/{productId}", cartId, productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void updateItemQuantityMapsResourceNotFoundWhenCartMissing() throws Exception {
        when(service.updateItemQuantity(eq(cartId), eq(productId), any()))
                .thenThrow(new ResourceNotFoundException("Cart not found"));

        UpdateCartItemQuantityRequest request = new UpdateCartItemQuantityRequest(5);
        mvc.perform(patch("/api/v1/carts/{cartId}/items/{productId}", cartId, productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void updateItemQuantityMapsResourceNotFoundWhenItemMissing() throws Exception {
        when(service.updateItemQuantity(eq(cartId), eq(productId), any()))
                .thenThrow(new ResourceNotFoundException("Cart item not found"));

        UpdateCartItemQuantityRequest request = new UpdateCartItemQuantityRequest(5);
        mvc.perform(patch("/api/v1/carts/{cartId}/items/{productId}", cartId, productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Cart item not found"));
    }

    @Test
    void updateItemQuantityWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        UpdateCartItemQuantityRequest request = new UpdateCartItemQuantityRequest(5);
        mvc.perform(patch("/api/v1/carts/{cartId}/items/{productId}", "not-a-uuid", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void updateItemQuantityRejectsMalformedJsonBody() throws Exception {
        mvc.perform(patch("/api/v1/carts/{cartId}/items/{productId}", cartId, productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- remove item ----------

    @Test
    void removeItemReturnsNoContent() throws Exception {
        when(service.removeItem(cartId, productId)).thenReturn(cartResponse());

        mvc.perform(delete("/api/v1/carts/{cartId}/items/{productId}", cartId, productId))
                .andExpect(status().isNoContent());

        verify(service).removeItem(cartId, productId);
    }

    @Test
    void removeItemMapsResourceNotFoundWhenCartMissing() throws Exception {
        when(service.removeItem(cartId, productId)).thenThrow(new ResourceNotFoundException("Cart not found"));

        mvc.perform(delete("/api/v1/carts/{cartId}/items/{productId}", cartId, productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void removeItemMapsResourceNotFoundWhenItemMissing() throws Exception {
        when(service.removeItem(cartId, productId)).thenThrow(new ResourceNotFoundException("Cart item not found"));

        mvc.perform(delete("/api/v1/carts/{cartId}/items/{productId}", cartId, productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Cart item not found"));
    }

    @Test
    void removeItemWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(delete("/api/v1/carts/{cartId}/items/{productId}", "not-a-uuid", productId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- clear cart ----------

    @Test
    void clearCartReturnsNoContent() throws Exception {
        when(service.clearCart(cartId)).thenReturn(cartResponse());

        mvc.perform(delete("/api/v1/carts/{cartId}/items", cartId))
                .andExpect(status().isNoContent());

        verify(service).clearCart(cartId);
    }

    @Test
    void clearCartMapsResourceNotFoundWhenCartMissing() throws Exception {
        when(service.clearCart(cartId)).thenThrow(new ResourceNotFoundException("Cart not found"));

        mvc.perform(delete("/api/v1/carts/{cartId}/items", cartId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void clearCartWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(delete("/api/v1/carts/{cartId}/items", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }
}
