package com.sumedha.commerce.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sumedha.commerce.product.dto.request.CreateProductAttributeRequest;
import com.sumedha.commerce.product.dto.response.ProductAttributeResponse;
import com.sumedha.commerce.product.exception.GlobalExceptionHandler;
import com.sumedha.commerce.product.service.ProductAttributeService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductAttributeControllerTest {

    MockMvc mvc;
    ProductAttributeService service;
    ObjectMapper mapper = new ObjectMapper();
    UUID productId = UUID.randomUUID();
    UUID attributeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ProductAttributeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProductAttributeController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createReturnsCreatedWithBody() throws Exception {
        ProductAttributeResponse response = new ProductAttributeResponse(attributeId, "Color", "Red");
        when(service.create(eq(productId), any())).thenReturn(response);

        CreateProductAttributeRequest request = new CreateProductAttributeRequest("Color", "Red");
        mvc.perform(post("/api/v1/products/{productId}/attributes", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Color"))
                .andExpect(jsonPath("$.data.value").value("Red"));

        verify(service).create(eq(productId), any());
    }

    @Test
    void createRejectsBlankName() throws Exception {
        CreateProductAttributeRequest request = new CreateProductAttributeRequest("", "Red");

        mvc.perform(post("/api/v1/products/{productId}/attributes", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsBlankValue() throws Exception {
        CreateProductAttributeRequest request = new CreateProductAttributeRequest("Color", "");

        mvc.perform(post("/api/v1/products/{productId}/attributes", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createMapsResourceNotFoundWhenProductMissing() throws Exception {
        when(service.create(eq(productId), any())).thenThrow(new ResourceNotFoundException("Product not found"));

        CreateProductAttributeRequest request = new CreateProductAttributeRequest("Color", "Red");
        mvc.perform(post("/api/v1/products/{productId}/attributes", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void listReturnsAttributes() throws Exception {
        ProductAttributeResponse response = new ProductAttributeResponse(attributeId, "Color", "Red");
        when(service.list(productId)).thenReturn(List.of(response));

        mvc.perform(get("/api/v1/products/{productId}/attributes", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void listMapsResourceNotFoundWhenProductMissing() throws Exception {
        when(service.list(productId)).thenThrow(new ResourceNotFoundException("Product not found"));

        mvc.perform(get("/api/v1/products/{productId}/attributes", productId))
                .andExpect(status().isNotFound());
    }

    @Test
    void listWithMalformedProductIdPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/products/{productId}/attributes", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        doNothing().when(service).delete(productId, attributeId);

        mvc.perform(delete("/api/v1/products/{productId}/attributes/{attributeId}", productId, attributeId))
                .andExpect(status().isNoContent());

        verify(service).delete(productId, attributeId);
    }

    @Test
    void deleteMapsResourceNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Product attribute not found")).when(service).delete(productId, attributeId);

        mvc.perform(delete("/api/v1/products/{productId}/attributes/{attributeId}", productId, attributeId))
                .andExpect(status().isNotFound());
    }
}
