package com.sumedha.commerce.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sumedha.commerce.product.dto.request.CreateBrandRequest;
import com.sumedha.commerce.product.dto.request.UpdateBrandRequest;
import com.sumedha.commerce.product.dto.response.BrandResponse;
import com.sumedha.commerce.product.exception.GlobalExceptionHandler;
import com.sumedha.commerce.product.service.BrandService;
import com.sumedha.commerce.common.core.exception.ConflictException;
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

class BrandControllerTest {

    MockMvc mvc;
    BrandService service;
    ObjectMapper mapper = new ObjectMapper();
    UUID brandId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(BrandService.class);
        mvc = MockMvcBuilders.standaloneSetup(new BrandController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createReturnsCreatedWithBody() throws Exception {
        BrandResponse response = new BrandResponse(brandId, "Acme", "acme", "desc", true);
        when(service.create(any())).thenReturn(response);

        CreateBrandRequest request = new CreateBrandRequest("Acme", "acme", "desc");
        mvc.perform(post("/api/v1/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.brandId").value(brandId.toString()))
                .andExpect(jsonPath("$.data.name").value("Acme"));

        verify(service).create(any());
    }

    @Test
    void createRejectsBlankName() throws Exception {
        CreateBrandRequest request = new CreateBrandRequest("", "acme", null);

        mvc.perform(post("/api/v1/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsInvalidSlugPattern() throws Exception {
        CreateBrandRequest request = new CreateBrandRequest("Acme", "Not A Slug", null);

        mvc.perform(post("/api/v1/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createMapsConflictException() throws Exception {
        when(service.create(any())).thenThrow(new ConflictException("Brand slug already exists"));

        CreateBrandRequest request = new CreateBrandRequest("Acme", "acme", null);
        mvc.perform(post("/api/v1/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Brand slug already exists"));
    }

    @Test
    void listReturnsAllBrands() throws Exception {
        BrandResponse response = new BrandResponse(brandId, "Acme", "acme", "desc", true);
        when(service.list()).thenReturn(List.of(response));

        mvc.perform(get("/api/v1/brands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void getReturnsBrand() throws Exception {
        BrandResponse response = new BrandResponse(brandId, "Acme", "acme", "desc", true);
        when(service.get(brandId)).thenReturn(response);

        mvc.perform(get("/api/v1/brands/{brandId}", brandId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brandId").value(brandId.toString()));
    }

    @Test
    void getMapsResourceNotFound() throws Exception {
        when(service.get(brandId)).thenThrow(new ResourceNotFoundException("Brand not found"));

        mvc.perform(get("/api/v1/brands/{brandId}", brandId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Brand not found"));
    }

    @Test
    void getWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/brands/{brandId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void updateReturnsUpdatedBrand() throws Exception {
        BrandResponse response = new BrandResponse(brandId, "Acme Updated", "acme-updated", "new desc", true);
        when(service.update(eq(brandId), any())).thenReturn(response);

        UpdateBrandRequest request = new UpdateBrandRequest("Acme Updated", "acme-updated", "new desc", true);
        mvc.perform(put("/api/v1/brands/{brandId}", brandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Acme Updated"));
    }

    @Test
    void updateRejectsBlankName() throws Exception {
        UpdateBrandRequest request = new UpdateBrandRequest("", "acme", null, true);

        mvc.perform(put("/api/v1/brands/{brandId}", brandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMapsConflictException() throws Exception {
        when(service.update(eq(brandId), any())).thenThrow(new ConflictException("Brand slug already exists"));

        UpdateBrandRequest request = new UpdateBrandRequest("Acme", "acme", null, true);
        mvc.perform(put("/api/v1/brands/{brandId}", brandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void updateMapsResourceNotFound() throws Exception {
        when(service.update(eq(brandId), any())).thenThrow(new ResourceNotFoundException("Brand not found"));

        UpdateBrandRequest request = new UpdateBrandRequest("Acme", "acme", null, true);
        mvc.perform(put("/api/v1/brands/{brandId}", brandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        doNothing().when(service).delete(brandId);

        mvc.perform(delete("/api/v1/brands/{brandId}", brandId))
                .andExpect(status().isNoContent());

        verify(service).delete(brandId);
    }

    @Test
    void deleteMapsResourceNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Brand not found")).when(service).delete(brandId);

        mvc.perform(delete("/api/v1/brands/{brandId}", brandId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteMapsConflictExceptionWhenBlocked() throws Exception {
        doThrow(new ConflictException("Brand has products")).when(service).delete(brandId);

        mvc.perform(delete("/api/v1/brands/{brandId}", brandId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Brand has products"));
    }
}
