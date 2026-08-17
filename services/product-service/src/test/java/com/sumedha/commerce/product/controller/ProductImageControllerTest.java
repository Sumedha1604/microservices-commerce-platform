package com.sumedha.commerce.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sumedha.commerce.product.dto.request.CreateProductImageRequest;
import com.sumedha.commerce.product.dto.request.UpdateProductImageRequest;
import com.sumedha.commerce.product.dto.response.ProductImageResponse;
import com.sumedha.commerce.product.exception.GlobalExceptionHandler;
import com.sumedha.commerce.product.service.ProductImageService;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductImageControllerTest {

    MockMvc mvc;
    ProductImageService service;
    ObjectMapper mapper = new ObjectMapper();
    UUID productId = UUID.randomUUID();
    UUID imageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ProductImageService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProductImageController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createReturnsCreatedWithBody() throws Exception {
        ProductImageResponse response = new ProductImageResponse(imageId, "http://img/1.png", "alt", 0, true);
        when(service.create(eq(productId), any())).thenReturn(response);

        CreateProductImageRequest request = new CreateProductImageRequest("http://img/1.png", "alt", 0, true);
        mvc.perform(post("/api/v1/products/{productId}/images", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.imageId").value(imageId.toString()));

        verify(service).create(eq(productId), any());
    }

    @Test
    void createRejectsBlankUrl() throws Exception {
        CreateProductImageRequest request = new CreateProductImageRequest("", "alt", 0, false);

        mvc.perform(post("/api/v1/products/{productId}/images", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsNegativeSortOrder() throws Exception {
        String json = "{\"url\":\"http://img/1.png\",\"altText\":\"alt\",\"sortOrder\":-1,\"primaryImage\":false}";

        mvc.perform(post("/api/v1/products/{productId}/images", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createMapsResourceNotFoundWhenProductMissing() throws Exception {
        when(service.create(eq(productId), any())).thenThrow(new ResourceNotFoundException("Product not found"));

        CreateProductImageRequest request = new CreateProductImageRequest("http://img/1.png", null, 0, false);
        mvc.perform(post("/api/v1/products/{productId}/images", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void listReturnsImages() throws Exception {
        ProductImageResponse response = new ProductImageResponse(imageId, "http://img/1.png", "alt", 0, true);
        when(service.list(productId)).thenReturn(List.of(response));

        mvc.perform(get("/api/v1/products/{productId}/images", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void listMapsResourceNotFoundWhenProductMissing() throws Exception {
        when(service.list(productId)).thenThrow(new ResourceNotFoundException("Product not found"));

        mvc.perform(get("/api/v1/products/{productId}/images", productId))
                .andExpect(status().isNotFound());
    }

    @Test
    void listWithMalformedProductIdPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/products/{productId}/images", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void updateReturnsUpdatedImage() throws Exception {
        ProductImageResponse response = new ProductImageResponse(imageId, "http://img/new.png", "new alt", 1, false);
        when(service.update(eq(productId), eq(imageId), any())).thenReturn(response);

        UpdateProductImageRequest request = new UpdateProductImageRequest("http://img/new.png", "new alt", 1, false);
        mvc.perform(put("/api/v1/products/{productId}/images/{imageId}", productId, imageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("http://img/new.png"));
    }

    @Test
    void updateRejectsBlankUrl() throws Exception {
        UpdateProductImageRequest request = new UpdateProductImageRequest("", null, 0, false);

        mvc.perform(put("/api/v1/products/{productId}/images/{imageId}", productId, imageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMapsResourceNotFoundWhenImageMissing() throws Exception {
        when(service.update(eq(productId), eq(imageId), any())).thenThrow(new ResourceNotFoundException("Product image not found"));

        UpdateProductImageRequest request = new UpdateProductImageRequest("http://img/x.png", null, 0, false);
        mvc.perform(put("/api/v1/products/{productId}/images/{imageId}", productId, imageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        doNothing().when(service).delete(productId, imageId);

        mvc.perform(delete("/api/v1/products/{productId}/images/{imageId}", productId, imageId))
                .andExpect(status().isNoContent());

        verify(service).delete(productId, imageId);
    }

    @Test
    void deleteMapsResourceNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Product image not found")).when(service).delete(productId, imageId);

        mvc.perform(delete("/api/v1/products/{productId}/images/{imageId}", productId, imageId))
                .andExpect(status().isNotFound());
    }
}
