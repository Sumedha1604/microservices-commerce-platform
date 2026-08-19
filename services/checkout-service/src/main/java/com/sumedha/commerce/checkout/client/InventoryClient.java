package com.sumedha.commerce.checkout.client;

import com.sumedha.commerce.checkout.dto.downstream.inventory.InventoryGetResponse;
import com.sumedha.commerce.checkout.dto.downstream.inventory.ReserveReleaseInventoryRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class InventoryClient extends DownstreamClient {

    private static final ParameterizedTypeReference<DownstreamApiResponse<InventoryGetResponse>> INVENTORY_RESPONSE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public InventoryClient(@Value("${checkout.services.inventory-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public InventoryGetResponse getInventoryByProductId(UUID productId) {
        return execute(() -> restClient.get()
                .uri("/api/v1/inventory/product/{productId}", productId)
                .retrieve()
                .body(INVENTORY_RESPONSE), "inventory");
    }

    public InventoryGetResponse reserve(UUID inventoryId, ReserveReleaseInventoryRequest request) {
        return updateStock(inventoryId, "reserve", request);
    }

    public InventoryGetResponse release(UUID inventoryId, ReserveReleaseInventoryRequest request) {
        return updateStock(inventoryId, "release", request);
    }

    private InventoryGetResponse updateStock(
            UUID inventoryId,
            String action,
            ReserveReleaseInventoryRequest request) {
        return execute(() -> restClient.post()
                .uri("/api/v1/inventory/{inventoryId}/{action}", inventoryId, action)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(INVENTORY_RESPONSE), "inventory");
    }
}
