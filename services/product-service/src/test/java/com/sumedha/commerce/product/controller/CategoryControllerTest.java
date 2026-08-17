package com.sumedha.commerce.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sumedha.commerce.product.dto.request.CreateCategoryRequest;
import com.sumedha.commerce.product.dto.request.UpdateCategoryRequest;
import com.sumedha.commerce.product.dto.response.CategoryResponse;
import com.sumedha.commerce.product.exception.GlobalExceptionHandler;
import com.sumedha.commerce.product.service.CategoryService;
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

class CategoryControllerTest {

    MockMvc mvc;
    CategoryService service;
    ObjectMapper mapper = new ObjectMapper();
    UUID categoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(CategoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CategoryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createReturnsCreatedWithBody() throws Exception {
        CategoryResponse response = new CategoryResponse(categoryId, "Electronics", "electronics", "desc", null, true);
        when(service.create(any())).thenReturn(response);

        CreateCategoryRequest request = new CreateCategoryRequest("Electronics", "electronics", "desc", null);
        mvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.categoryId").value(categoryId.toString()))
                .andExpect(jsonPath("$.data.name").value("Electronics"));

        verify(service).create(any());
    }

    @Test
    void createRejectsBlankName() throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest("", "electronics", null, null);

        mvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsInvalidSlugPattern() throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest("Electronics", "Invalid Slug!", null, null);

        mvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsMalformedJsonBody() throws Exception {
        mvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createMapsConflictException() throws Exception {
        when(service.create(any())).thenThrow(new ConflictException("Category slug already exists"));

        CreateCategoryRequest request = new CreateCategoryRequest("Electronics", "electronics", null, null);
        mvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Category slug already exists"));
    }

    @Test
    void listReturnsAllCategories() throws Exception {
        CategoryResponse response = new CategoryResponse(categoryId, "Electronics", "electronics", "desc", null, true);
        when(service.list()).thenReturn(List.of(response));

        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void getReturnsCategory() throws Exception {
        CategoryResponse response = new CategoryResponse(categoryId, "Electronics", "electronics", "desc", null, true);
        when(service.get(categoryId)).thenReturn(response);

        mvc.perform(get("/api/v1/categories/{categoryId}", categoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryId").value(categoryId.toString()));
    }

    @Test
    void getMapsResourceNotFound() throws Exception {
        when(service.get(categoryId)).thenThrow(new ResourceNotFoundException("Category not found"));

        mvc.perform(get("/api/v1/categories/{categoryId}", categoryId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Category not found"));
    }

    @Test
    void getWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/categories/{categoryId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void updateReturnsUpdatedCategory() throws Exception {
        CategoryResponse response = new CategoryResponse(categoryId, "Gadgets", "gadgets", "new desc", null, true);
        when(service.update(eq(categoryId), any())).thenReturn(response);

        UpdateCategoryRequest request = new UpdateCategoryRequest("Gadgets", "gadgets", "new desc", null, true);
        mvc.perform(put("/api/v1/categories/{categoryId}", categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Gadgets"));
    }

    @Test
    void updateRejectsBlankSlug() throws Exception {
        UpdateCategoryRequest request = new UpdateCategoryRequest("Gadgets", "", null, null, true);

        mvc.perform(put("/api/v1/categories/{categoryId}", categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMapsConflictException() throws Exception {
        when(service.update(eq(categoryId), any())).thenThrow(new ConflictException("Category slug already exists"));

        UpdateCategoryRequest request = new UpdateCategoryRequest("Gadgets", "gadgets", null, null, true);
        mvc.perform(put("/api/v1/categories/{categoryId}", categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void updateMapsResourceNotFound() throws Exception {
        when(service.update(eq(categoryId), any())).thenThrow(new ResourceNotFoundException("Category not found"));

        UpdateCategoryRequest request = new UpdateCategoryRequest("Gadgets", "gadgets", null, null, true);
        mvc.perform(put("/api/v1/categories/{categoryId}", categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        doNothing().when(service).delete(categoryId);

        mvc.perform(delete("/api/v1/categories/{categoryId}", categoryId))
                .andExpect(status().isNoContent());

        verify(service).delete(categoryId);
    }

    @Test
    void deleteMapsResourceNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Category not found")).when(service).delete(categoryId);

        mvc.perform(delete("/api/v1/categories/{categoryId}", categoryId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteMapsConflictExceptionWhenBlocked() throws Exception {
        doThrow(new ConflictException("Category has products")).when(service).delete(categoryId);

        mvc.perform(delete("/api/v1/categories/{categoryId}", categoryId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Category has products"));
    }

    @Test
    void unexpectedExceptionMapsToInternalServerErrorWithoutLeakingDetails() throws Exception {
        when(service.get(categoryId)).thenThrow(new IllegalStateException("db connection pool exhausted at 10.0.0.5"));

        mvc.perform(get("/api/v1/categories/{categoryId}", categoryId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("db connection pool"))));
    }
}
