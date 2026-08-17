package com.sumedha.commerce.product.service;

import com.sumedha.commerce.product.dto.request.CreateProductRequest;
import com.sumedha.commerce.product.dto.request.ProductFilter;
import com.sumedha.commerce.product.dto.request.UpdateProductRequest;
import com.sumedha.commerce.product.dto.response.ProductAttributeResponse;
import com.sumedha.commerce.product.dto.response.ProductImageResponse;
import com.sumedha.commerce.product.dto.response.ProductResponse;
import com.sumedha.commerce.product.dto.response.ProductSummaryResponse;
import com.sumedha.commerce.product.entity.Brand;
import com.sumedha.commerce.product.entity.Category;
import com.sumedha.commerce.product.entity.Product;
import com.sumedha.commerce.product.entity.ProductAttribute;
import com.sumedha.commerce.product.entity.ProductImage;
import com.sumedha.commerce.product.enums.ProductStatus;
import com.sumedha.commerce.product.repository.BrandRepository;
import com.sumedha.commerce.product.repository.CategoryRepository;
import com.sumedha.commerce.product.repository.ProductAttributeRepository;
import com.sumedha.commerce.product.repository.ProductImageRepository;
import com.sumedha.commerce.product.repository.ProductRepository;
import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.common.core.enums.SortDirection;
import com.sumedha.commerce.common.core.pagination.PageRequest;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    ProductRepository products;
    @Mock
    CategoryRepository categories;
    @Mock
    BrandRepository brands;
    @Mock
    ProductImageRepository images;
    @Mock
    ProductAttributeRepository attributes;

    ProductServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductServiceImpl(products, categories, brands, images, attributes);
    }

    private Category sampleCategory() {
        return new Category("Electronics", "electronics", "desc", null);
    }

    private Brand sampleBrand() {
        return new Brand("Acme", "acme", "desc");
    }

    private Product sampleProduct(Category category, Brand brand) {
        return new Product("SKU-1", "Product One", "product-1", category.getId(),
                brand == null ? null : brand.getId(), BigDecimal.valueOf(19.99), "USD");
    }

    private ProductFilter emptyFilter() {
        return new ProductFilter(null, null, null, null, null, null);
    }

    private Page<Product> pageOf(Product product) {
        return new PageImpl<>(List.of(product), org.springframework.data.domain.PageRequest.of(0, 10), 1);
    }

    // --- create ---

    @Test
    void createsProductWithoutBrand() {
        Category category = sampleCategory();
        when(products.existsBySku("SKU-1")).thenReturn(false);
        when(products.existsBySlug("product-1")).thenReturn(false);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(products.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                category.getId(), null, BigDecimal.valueOf(19.99), "USD");
        ProductResponse response = service.create(request);

        assertEquals("SKU-1", response.sku());
        assertEquals("product-1", response.slug());
        assertNull(response.brand());
        assertEquals(category.getId(), response.category().categoryId());
        verify(brands, never()).findById(any());
    }

    @Test
    void createsProductWithBrand() {
        Category category = sampleCategory();
        Brand brand = sampleBrand();
        when(products.existsBySku("SKU-1")).thenReturn(false);
        when(products.existsBySlug("product-1")).thenReturn(false);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(brands.findById(brand.getId())).thenReturn(Optional.of(brand));
        when(products.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                category.getId(), brand.getId(), BigDecimal.valueOf(19.99), "USD");
        ProductResponse response = service.create(request);

        assertEquals(brand.getId(), response.brand().brandId());
    }

    @Test
    void createRejectsDuplicateSku() {
        when(products.existsBySku("SKU-1")).thenReturn(true);

        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                UUID.randomUUID(), null, BigDecimal.TEN, "USD");
        assertThrows(ConflictException.class, () -> service.create(request));
        verify(products, never()).save(any());
    }

    @Test
    void createRejectsDuplicateSlug() {
        when(products.existsBySku("SKU-1")).thenReturn(false);
        when(products.existsBySlug("product-1")).thenReturn(true);

        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                UUID.randomUUID(), null, BigDecimal.TEN, "USD");
        assertThrows(ConflictException.class, () -> service.create(request));
        verify(products, never()).save(any());
    }

    @Test
    void createRejectsMissingCategory() {
        UUID categoryId = UUID.randomUUID();
        when(products.existsBySku("SKU-1")).thenReturn(false);
        when(products.existsBySlug("product-1")).thenReturn(false);
        when(categories.findById(categoryId)).thenReturn(Optional.empty());

        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                categoryId, null, BigDecimal.TEN, "USD");
        assertThrows(ResourceNotFoundException.class, () -> service.create(request));
        verify(products, never()).save(any());
    }

    @Test
    void createRejectsMissingBrand() {
        Category category = sampleCategory();
        UUID brandId = UUID.randomUUID();
        when(products.existsBySku("SKU-1")).thenReturn(false);
        when(products.existsBySlug("product-1")).thenReturn(false);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(brands.findById(brandId)).thenReturn(Optional.empty());

        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                category.getId(), brandId, BigDecimal.TEN, "USD");
        assertThrows(ResourceNotFoundException.class, () -> service.create(request));
        verify(products, never()).save(any());
    }

    // --- get / getBySlug ---

    @Test
    void getReturnsProduct() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));

        ProductResponse response = service.get(product.getId());

        assertEquals(product.getId(), response.productId());
    }

    @Test
    void getThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(products.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(id));
    }

    @Test
    void getBySlugReturnsProduct() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findBySlug("product-1")).thenReturn(Optional.of(product));
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));

        ProductResponse response = service.getBySlug("product-1");

        assertEquals(product.getSlug(), response.slug());
    }

    @Test
    void getBySlugThrowsWhenMissing() {
        when(products.findBySlug("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getBySlug("missing"));
    }

    // --- images / attributes ---

    @Test
    void getReturnsPersistedImagesAndAttributes() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        ProductImage image = new ProductImage(product.getId(), "https://example.com/a.png", "alt", 0, true);
        ProductAttribute attribute = new ProductAttribute(product.getId(), "Color", "Red");
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(images.findByProductIdOrderBySortOrderAsc(product.getId())).thenReturn(List.of(image));
        when(attributes.findByProductId(product.getId())).thenReturn(List.of(attribute));

        ProductResponse response = service.get(product.getId());

        assertEquals(1, response.images().size());
        ProductImageResponse imageResponse = response.images().get(0);
        assertEquals(image.getId(), imageResponse.imageId());
        assertEquals(image.getUrl(), imageResponse.url());

        assertEquals(1, response.attributes().size());
        ProductAttributeResponse attributeResponse = response.attributes().get(0);
        assertEquals(attribute.getId(), attributeResponse.attributeId());
        assertEquals(attribute.getName(), attributeResponse.name());
        assertEquals(attribute.getValue(), attributeResponse.value());
    }

    @Test
    void getPreservesRepositoryImageSortOrder() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        ProductImage first = new ProductImage(product.getId(), "https://example.com/first.png", "first", 0, true);
        ProductImage second = new ProductImage(product.getId(), "https://example.com/second.png", "second", 1, false);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(images.findByProductIdOrderBySortOrderAsc(product.getId())).thenReturn(List.of(first, second));

        ProductResponse response = service.get(product.getId());

        assertEquals(List.of(first.getId(), second.getId()),
                response.images().stream().map(ProductImageResponse::imageId).toList());
    }

    @Test
    void getReturnsEmptyImagesAndAttributesWhenNonePersisted() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(images.findByProductIdOrderBySortOrderAsc(product.getId())).thenReturn(List.of());
        when(attributes.findByProductId(product.getId())).thenReturn(List.of());

        ProductResponse response = service.get(product.getId());

        assertEquals(List.of(), response.images());
        assertEquals(List.of(), response.attributes());
    }

    @Test
    void createReturnsPersistedImagesAndAttributesForNewProduct() {
        Category category = sampleCategory();
        when(products.existsBySku("SKU-1")).thenReturn(false);
        when(products.existsBySlug("product-1")).thenReturn(false);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(products.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));
        when(images.findByProductIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(attributes.findByProductId(any())).thenReturn(List.of());

        CreateProductRequest request = new CreateProductRequest("SKU-1", "Product One", "product-1",
                category.getId(), null, BigDecimal.valueOf(19.99), "USD");
        ProductResponse response = service.create(request);

        assertEquals(List.of(), response.images());
        assertEquals(List.of(), response.attributes());
    }

    @Test
    void updateReturnsPersistedImagesAndAttributes() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        ProductImage image = new ProductImage(product.getId(), "https://example.com/a.png", "alt", 0, true);
        ProductAttribute attribute = new ProductAttribute(product.getId(), "Color", "Red");
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(products.existsBySlugAndIdNot("new-slug", product.getId())).thenReturn(false);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(images.findByProductIdOrderBySortOrderAsc(product.getId())).thenReturn(List.of(image));
        when(attributes.findByProductId(product.getId())).thenReturn(List.of(attribute));

        UpdateProductRequest request = new UpdateProductRequest("New Name", "new-slug", "short", "long",
                category.getId(), null, BigDecimal.valueOf(29.99), "USD", ProductStatus.ACTIVE, true);
        ProductResponse response = service.update(product.getId(), request);

        assertEquals(1, response.images().size());
        assertEquals(1, response.attributes().size());
    }

    // --- update ---

    @Test
    void updatesProduct() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(products.existsBySlugAndIdNot("new-slug", product.getId())).thenReturn(false);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));

        UpdateProductRequest request = new UpdateProductRequest("New Name", "new-slug", "short", "long",
                category.getId(), null, BigDecimal.valueOf(29.99), "USD", ProductStatus.ACTIVE, true);
        ProductResponse response = service.update(product.getId(), request);

        assertEquals("New Name", response.name());
        assertEquals("new-slug", response.slug());
        assertEquals(ProductStatus.ACTIVE, response.status());
    }

    @Test
    void updateDoesNotChangeSku() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));

        UpdateProductRequest request = new UpdateProductRequest("New Name", "product-1", null, null,
                category.getId(), null, BigDecimal.TEN, "USD", ProductStatus.ACTIVE, true);
        ProductResponse response = service.update(product.getId(), request);

        assertEquals("SKU-1", response.sku());
        assertEquals(product.getSku(), response.sku());
    }

    @Test
    void updateRejectsDuplicateSlug() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(products.existsBySlugAndIdNot("new-slug", product.getId())).thenReturn(true);

        UpdateProductRequest request = new UpdateProductRequest("New Name", "new-slug", null, null,
                category.getId(), null, BigDecimal.TEN, "USD", ProductStatus.ACTIVE, true);
        assertThrows(ConflictException.class, () -> service.update(product.getId(), request));
    }

    @Test
    void updateRejectsMissingCategory() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        UUID missingCategoryId = UUID.randomUUID();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(categories.findById(missingCategoryId)).thenReturn(Optional.empty());

        UpdateProductRequest request = new UpdateProductRequest("New Name", "product-1", null, null,
                missingCategoryId, null, BigDecimal.TEN, "USD", ProductStatus.ACTIVE, true);
        assertThrows(ResourceNotFoundException.class, () -> service.update(product.getId(), request));
    }

    @Test
    void updateRejectsMissingBrand() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        UUID missingBrandId = UUID.randomUUID();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(brands.findById(missingBrandId)).thenReturn(Optional.empty());

        UpdateProductRequest request = new UpdateProductRequest("New Name", "product-1", null, null,
                category.getId(), missingBrandId, BigDecimal.TEN, "USD", ProductStatus.ACTIVE, true);
        assertThrows(ResourceNotFoundException.class, () -> service.update(product.getId(), request));
    }

    @Test
    void updateThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(products.findById(id)).thenReturn(Optional.empty());

        UpdateProductRequest request = new UpdateProductRequest("Name", "slug", null, null,
                UUID.randomUUID(), null, BigDecimal.TEN, "USD", ProductStatus.ACTIVE, true);
        assertThrows(ResourceNotFoundException.class, () -> service.update(id, request));
    }

    // --- delete ---

    @Test
    void deletesProduct() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.existsByProductId(product.getId())).thenReturn(false);
        when(attributes.existsByProductId(product.getId())).thenReturn(false);

        service.delete(product.getId());

        verify(products).delete(product);
    }

    @Test
    void deleteThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(products.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(id));
        verify(products, never()).delete(any(Product.class));
    }

    @Test
    void deleteBlockedByImages() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.existsByProductId(product.getId())).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.delete(product.getId()));
        verify(products, never()).delete(any(Product.class));
    }

    @Test
    void deleteBlockedByAttributes() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.existsByProductId(product.getId())).thenReturn(false);
        when(attributes.existsByProductId(product.getId())).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.delete(product.getId()));
        verify(products, never()).delete(any(Product.class));
    }

    // --- search ---

    @Test
    @SuppressWarnings("unchecked")
    void searchWithNoFilters() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf(product));

        PageResponse<ProductSummaryResponse> result = service.search(emptyFilter(),
                PageRequest.of(0, 10, SortDirection.ASC, "name"));

        assertEquals(1, result.getItems().size());
        assertEquals(1, result.getTotalElements());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithSearchText() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf(product));

        ProductFilter filter = new ProductFilter("product", null, null, null, null, null);
        PageResponse<ProductSummaryResponse> result = service.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "name"));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithCategoryFilter() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf(product));

        ProductFilter filter = new ProductFilter(null, category.getId(), null, null, null, null);
        PageResponse<ProductSummaryResponse> result = service.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "name"));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithBrandFilter() {
        Category category = sampleCategory();
        Brand brand = sampleBrand();
        Product product = sampleProduct(category, brand);
        when(products.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf(product));

        ProductFilter filter = new ProductFilter(null, null, brand.getId(), null, null, null);
        PageResponse<ProductSummaryResponse> result = service.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "name"));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithStatusFilter() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf(product));

        ProductFilter filter = new ProductFilter(null, null, null, ProductStatus.ACTIVE, null, null);
        PageResponse<ProductSummaryResponse> result = service.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "name"));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithMinPrice() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf(product));

        ProductFilter filter = new ProductFilter(null, null, null, null, BigDecimal.TEN, null);
        PageResponse<ProductSummaryResponse> result = service.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "price"));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithMaxPrice() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf(product));

        ProductFilter filter = new ProductFilter(null, null, null, null, null, BigDecimal.valueOf(100));
        PageResponse<ProductSummaryResponse> result = service.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "price"));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithCombinedFilters() {
        Category category = sampleCategory();
        Brand brand = sampleBrand();
        Product product = sampleProduct(category, brand);
        when(products.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf(product));

        ProductFilter filter = new ProductFilter("product", category.getId(), brand.getId(),
                ProductStatus.ACTIVE, BigDecimal.TEN, BigDecimal.valueOf(100));
        PageResponse<ProductSummaryResponse> result = service.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "price"));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void searchRejectsMinPriceGreaterThanMaxPrice() {
        ProductFilter filter = new ProductFilter(null, null, null, null, BigDecimal.valueOf(100), BigDecimal.TEN);

        assertThrows(BadRequestException.class,
                () -> service.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "name")));
        verifyNoInteractions(products);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "price", "createdAt", "updatedAt"})
    @SuppressWarnings("unchecked")
    void searchAllowsKnownSortFields(String sortField) {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf(product));

        assertDoesNotThrow(() -> service.search(emptyFilter(), PageRequest.of(0, 10, SortDirection.ASC, sortField)));
    }

    @Test
    void searchRejectsInvalidSortField() {
        ProductFilter filter = emptyFilter();

        assertThrows(BadRequestException.class,
                () -> service.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "invalidField")));
        verifyNoInteractions(products);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchMapsResultsToProductSummaryResponse() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        when(products.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf(product));

        PageResponse<ProductSummaryResponse> result = service.search(emptyFilter(),
                PageRequest.of(0, 10, SortDirection.ASC, "name"));

        ProductSummaryResponse summary = result.getItems().get(0);
        assertEquals(product.getId(), summary.productId());
        assertEquals(product.getSku(), summary.sku());
        assertEquals(product.getName(), summary.name());
        assertEquals(product.getSlug(), summary.slug());
        assertEquals(product.getPrice(), summary.price());
        assertEquals(product.getCurrency(), summary.currency());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchAppliesSortAndPagination() {
        Category category = sampleCategory();
        Product product = sampleProduct(category, null);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(products.findAll(any(Specification.class), pageableCaptor.capture())).thenReturn(pageOf(product));

        service.search(emptyFilter(), PageRequest.of(1, 5, SortDirection.DESC, "price"));

        Pageable captured = pageableCaptor.getValue();
        assertEquals(1, captured.getPageNumber());
        assertEquals(5, captured.getPageSize());
        assertTrue(captured.getSort().getOrderFor("price").isDescending());
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
