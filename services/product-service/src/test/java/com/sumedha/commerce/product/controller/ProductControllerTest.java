package com.sumedha.commerce.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sumedha.commerce.product.dto.request.CreateProductRequest;
import com.sumedha.commerce.product.dto.request.ProductFilter;
import com.sumedha.commerce.product.dto.request.UpdateProductRequest;
import com.sumedha.commerce.product.dto.response.CategoryResponse;
import com.sumedha.commerce.product.dto.response.ProductResponse;
import com.sumedha.commerce.product.dto.response.ProductSummaryResponse;
import com.sumedha.commerce.product.enums.ProductStatus;
import com.sumedha.commerce.product.exception.GlobalExceptionHandler;
import com.sumedha.commerce.product.service.ProductService;
import com.sumedha.commerce.common.core.enums.SortDirection;
import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.common.core.pagination.PageRequest;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class ProductControllerTest {

    MockMvc mvc;
    ProductService service;
    ObjectMapper mapper = new ObjectMapper();
    UUID productId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ProductService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProductController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ProductResponse sampleResponse() {
        CategoryResponse category = new CategoryResponse(categoryId, "Electronics", "electronics", null, null, true);
        return new ProductResponse(productId, "SKU-1", "Product One", "product-1", "short", "long",
                category, null, BigDecimal.valueOf(19.99), "USD", ProductStatus.DRAFT, true,
                List.of(), List.of(), Instant.now(), Instant.now());
    }

    // --- create ---

    @Test
    void createReturnsCreatedWithBody() throws Exception {
        when(service.create(any())).thenReturn(sampleResponse());

        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                categoryId, null, BigDecimal.valueOf(19.99), "USD");
        mvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sku").value("SKU-1"));

        verify(service).create(any());
    }

    @Test
    void createRejectsBlankSku() throws Exception {
        CreateProductRequest request = new CreateProductRequest("", "Product One", "product-1",
                categoryId, null, BigDecimal.valueOf(19.99), "USD");

        mvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void createRejectsMissingCategoryId() throws Exception {
        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                null, null, BigDecimal.valueOf(19.99), "USD");

        mvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsNegativePrice() throws Exception {
        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                categoryId, null, BigDecimal.valueOf(-5), "USD");

        mvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createMapsConflictExceptionOnDuplicateSku() throws Exception {
        when(service.create(any())).thenThrow(new ConflictException("Product SKU already exists"));

        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                categoryId, null, BigDecimal.valueOf(19.99), "USD");
        mvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Product SKU already exists"));
    }

    @Test
    void createMapsResourceNotFoundOnMissingCategory() throws Exception {
        when(service.create(any())).thenThrow(new ResourceNotFoundException("Category not found"));

        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                categoryId, null, BigDecimal.valueOf(19.99), "USD");
        mvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // --- get / getBySlug ---

    @Test
    void getReturnsProduct() throws Exception {
        when(service.get(productId)).thenReturn(sampleResponse());

        mvc.perform(get("/api/v1/products/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(productId.toString()));
    }

    @Test
    void getWithMalformedUuidPathVariableReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/products/{productId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void getMapsResourceNotFound() throws Exception {
        when(service.get(productId)).thenThrow(new ResourceNotFoundException("Product not found"));

        mvc.perform(get("/api/v1/products/{productId}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void getBySlugReturnsProduct() throws Exception {
        when(service.getBySlug("product-1")).thenReturn(sampleResponse());

        mvc.perform(get("/api/v1/products/slug/{slug}", "product-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("product-1"));
    }

    @Test
    void getBySlugMapsResourceNotFound() throws Exception {
        when(service.getBySlug("missing")).thenThrow(new ResourceNotFoundException("Product not found"));

        mvc.perform(get("/api/v1/products/slug/{slug}", "missing"))
                .andExpect(status().isNotFound());
    }

    // --- update ---

    @Test
    void updateReturnsUpdatedProduct() throws Exception {
        when(service.update(eq(productId), any())).thenReturn(sampleResponse());

        UpdateProductRequest request = new UpdateProductRequest("New Name", "product-1", null, null,
                categoryId, null, BigDecimal.valueOf(29.99), "USD", ProductStatus.ACTIVE, true);
        mvc.perform(put("/api/v1/products/{productId}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sku").value("SKU-1"));
    }

    @Test
    void updateRejectsBlankName() throws Exception {
        UpdateProductRequest request = new UpdateProductRequest("", "product-1", null, null,
                categoryId, null, BigDecimal.valueOf(29.99), "USD", ProductStatus.ACTIVE, true);

        mvc.perform(put("/api/v1/products/{productId}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRejectsMissingStatus() throws Exception {
        String json = "{\"name\":\"New Name\",\"slug\":\"product-1\",\"categoryId\":\"" + categoryId
                + "\",\"price\":29.99,\"active\":true}";

        mvc.perform(put("/api/v1/products/{productId}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMapsConflictException() throws Exception {
        when(service.update(eq(productId), any())).thenThrow(new ConflictException("Product slug already exists"));

        UpdateProductRequest request = new UpdateProductRequest("New Name", "product-1", null, null,
                categoryId, null, BigDecimal.valueOf(29.99), "USD", ProductStatus.ACTIVE, true);
        mvc.perform(put("/api/v1/products/{productId}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // --- delete ---

    @Test
    void deleteReturnsNoContent() throws Exception {
        doNothing().when(service).delete(productId);

        mvc.perform(delete("/api/v1/products/{productId}", productId))
                .andExpect(status().isNoContent());

        verify(service).delete(productId);
    }

    @Test
    void deleteMapsResourceNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Product not found")).when(service).delete(productId);

        mvc.perform(delete("/api/v1/products/{productId}", productId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteMapsConflictExceptionWhenBlockedByImages() throws Exception {
        doThrow(new ConflictException("Product has images")).when(service).delete(productId);

        mvc.perform(delete("/api/v1/products/{productId}", productId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Product has images"));
    }

    // --- search ---

    @Test
    void searchWithNoParamsUsesDefaults() throws Exception {
        ArgumentCaptor<ProductFilter> filterCaptor = ArgumentCaptor.forClass(ProductFilter.class);
        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        PageResponse<ProductSummaryResponse> page = PageResponse.of(List.of(), 0, 20, 0);
        when(service.search(filterCaptor.capture(), pageCaptor.capture())).thenReturn(page);

        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ProductFilter filter = filterCaptor.getValue();
        assertNull(filter.search());
        assertNull(filter.categoryId());
        assertNull(filter.brandId());
        assertNull(filter.status());
        assertNull(filter.minPrice());
        assertNull(filter.maxPrice());

        PageRequest pageRequest = pageCaptor.getValue();
        assertEquals(0, pageRequest.getPage());
        assertEquals(20, pageRequest.getSize());
        assertEquals("createdAt", pageRequest.getSortBy());
        assertEquals(SortDirection.ASC, pageRequest.getSortDirection());
    }

    @Test
    void searchConvertsQueryParamsToFilterAndPageRequest() throws Exception {
        UUID brandId = UUID.randomUUID();
        ArgumentCaptor<ProductFilter> filterCaptor = ArgumentCaptor.forClass(ProductFilter.class);
        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        PageResponse<ProductSummaryResponse> page = PageResponse.of(List.of(), 2, 5, 0);
        when(service.search(filterCaptor.capture(), pageCaptor.capture())).thenReturn(page);

        mvc.perform(get("/api/v1/products")
                        .param("search", "shoe")
                        .param("categoryId", categoryId.toString())
                        .param("brandId", brandId.toString())
                        .param("status", "ACTIVE")
                        .param("minPrice", "10")
                        .param("maxPrice", "100")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sortBy", "price")
                        .param("sortDirection", "DESC"))
                .andExpect(status().isOk());

        ProductFilter filter = filterCaptor.getValue();
        assertEquals("shoe", filter.search());
        assertEquals(categoryId, filter.categoryId());
        assertEquals(brandId, filter.brandId());
        assertEquals(ProductStatus.ACTIVE, filter.status());
        assertEquals(0, BigDecimal.valueOf(10).compareTo(filter.minPrice()));
        assertEquals(0, BigDecimal.valueOf(100).compareTo(filter.maxPrice()));

        PageRequest pageRequest = pageCaptor.getValue();
        assertEquals(2, pageRequest.getPage());
        assertEquals(5, pageRequest.getSize());
        assertEquals("price", pageRequest.getSortBy());
        assertEquals(SortDirection.DESC, pageRequest.getSortDirection());
    }

    @Test
    void searchMapsBadRequestException() throws Exception {
        when(service.search(any(), any())).thenThrow(new BadRequestException("minPrice must not be greater than maxPrice"));

        mvc.perform(get("/api/v1/products").param("minPrice", "100").param("maxPrice", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void searchWithMalformedUuidQueryParamReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/products").param("categoryId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void searchWithInvalidStatusQueryParamReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/products").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void searchWithInvalidNumericQueryParamReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/products").param("page", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }
}
