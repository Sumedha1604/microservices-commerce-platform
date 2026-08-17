package com.sumedha.commerce.product.service;

import com.sumedha.commerce.product.dto.request.CreateProductImageRequest;
import com.sumedha.commerce.product.dto.request.UpdateProductImageRequest;
import com.sumedha.commerce.product.dto.response.ProductImageResponse;
import com.sumedha.commerce.product.entity.Product;
import com.sumedha.commerce.product.entity.ProductImage;
import com.sumedha.commerce.product.repository.ProductImageRepository;
import com.sumedha.commerce.product.repository.ProductRepository;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductImageServiceImplTest {

    @Mock
    ProductRepository products;
    @Mock
    ProductImageRepository images;

    ProductImageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductImageServiceImpl(products, images);
    }

    private Product sampleProduct() {
        return new Product("SKU-1", "Product One", "product-1", UUID.randomUUID(), null, BigDecimal.TEN, "USD");
    }

    // --- create ---

    @Test
    void createsImage() {
        Product product = sampleProduct();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.save(any(ProductImage.class))).thenAnswer(i -> i.getArgument(0));

        CreateProductImageRequest request = new CreateProductImageRequest("http://img/1.png", "alt", 0, false);
        ProductImageResponse response = service.create(product.getId(), request);

        assertEquals("http://img/1.png", response.url());
        assertFalse(response.primaryImage());
    }

    @Test
    void createSkipsClearingPrimaryWhenNotPrimary() {
        Product product = sampleProduct();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.save(any(ProductImage.class))).thenAnswer(i -> i.getArgument(0));

        CreateProductImageRequest request = new CreateProductImageRequest("http://img/1.png", "alt", 0, false);
        service.create(product.getId(), request);

        verify(images, never()).findByProductIdAndPrimaryImageTrue(any());
    }

    @Test
    void createClearsExistingPrimaryImage() {
        Product product = sampleProduct();
        ProductImage existingPrimary = new ProductImage(product.getId(), "http://img/old.png", null, 0, true);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.findByProductIdAndPrimaryImageTrue(product.getId())).thenReturn(Optional.of(existingPrimary));
        when(images.save(any(ProductImage.class))).thenAnswer(i -> i.getArgument(0));

        CreateProductImageRequest request = new CreateProductImageRequest("http://img/new.png", "alt", 1, true);
        ProductImageResponse response = service.create(product.getId(), request);

        assertFalse(existingPrimary.isPrimaryImage());
        assertTrue(response.primaryImage());
    }

    @Test
    void createThrowsWhenProductMissing() {
        UUID productId = UUID.randomUUID();
        when(products.findById(productId)).thenReturn(Optional.empty());

        CreateProductImageRequest request = new CreateProductImageRequest("http://img/1.png", null, 0, false);
        assertThrows(ResourceNotFoundException.class, () -> service.create(productId, request));
        verify(images, never()).save(any());
    }

    // --- list ---

    @Test
    void listReturnsImagesOrderedBySortOrder() {
        Product product = sampleProduct();
        ProductImage img1 = new ProductImage(product.getId(), "http://img/1.png", null, 0, true);
        ProductImage img2 = new ProductImage(product.getId(), "http://img/2.png", null, 1, false);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.findByProductIdOrderBySortOrderAsc(product.getId())).thenReturn(List.of(img1, img2));

        List<ProductImageResponse> result = service.list(product.getId());

        assertEquals(2, result.size());
        assertEquals(img1.getUrl(), result.get(0).url());
        assertEquals(img2.getUrl(), result.get(1).url());
    }

    @Test
    void listThrowsWhenProductMissing() {
        UUID productId = UUID.randomUUID();
        when(products.findById(productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.list(productId));
    }

    // --- update ---

    @Test
    void updatesImage() {
        Product product = sampleProduct();
        ProductImage image = new ProductImage(product.getId(), "http://img/old.png", null, 0, false);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.findByIdAndProductId(image.getId(), product.getId())).thenReturn(Optional.of(image));

        UpdateProductImageRequest request = new UpdateProductImageRequest("http://img/new.png", "new alt", 2, false);
        ProductImageResponse response = service.update(product.getId(), image.getId(), request);

        assertEquals("http://img/new.png", response.url());
        assertEquals(2, response.sortOrder());
    }

    @Test
    void updateClearsOtherPrimaryImageWhenSettingPrimary() {
        Product product = sampleProduct();
        ProductImage target = new ProductImage(product.getId(), "http://img/target.png", null, 0, false);
        ProductImage otherPrimary = new ProductImage(product.getId(), "http://img/other.png", null, 1, true);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.findByIdAndProductId(target.getId(), product.getId())).thenReturn(Optional.of(target));
        when(images.findByProductIdAndPrimaryImageTrue(product.getId())).thenReturn(Optional.of(otherPrimary));

        UpdateProductImageRequest request = new UpdateProductImageRequest("http://img/target.png", null, 0, true);
        ProductImageResponse response = service.update(product.getId(), target.getId(), request);

        assertFalse(otherPrimary.isPrimaryImage());
        assertTrue(response.primaryImage());
    }

    @Test
    void updateSkipsClearingPrimaryForSameImage() {
        Product product = sampleProduct();
        ProductImage target = spy(new ProductImage(product.getId(), "http://img/target.png", null, 0, true));
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.findByIdAndProductId(target.getId(), product.getId())).thenReturn(Optional.of(target));
        when(images.findByProductIdAndPrimaryImageTrue(product.getId())).thenReturn(Optional.of(target));

        UpdateProductImageRequest request = new UpdateProductImageRequest("http://img/target.png", null, 0, true);
        service.update(product.getId(), target.getId(), request);

        verify(target, never()).setPrimaryImage(false);
    }

    @Test
    void updateThrowsWhenImageMissing() {
        Product product = sampleProduct();
        UUID imageId = UUID.randomUUID();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.findByIdAndProductId(imageId, product.getId())).thenReturn(Optional.empty());

        UpdateProductImageRequest request = new UpdateProductImageRequest("http://img/x.png", null, 0, false);
        assertThrows(ResourceNotFoundException.class, () -> service.update(product.getId(), imageId, request));
    }

    @Test
    void updateThrowsWhenProductMissing() {
        UUID productId = UUID.randomUUID();
        when(products.findById(productId)).thenReturn(Optional.empty());

        UpdateProductImageRequest request = new UpdateProductImageRequest("http://img/x.png", null, 0, false);
        assertThrows(ResourceNotFoundException.class,
                () -> service.update(productId, UUID.randomUUID(), request));
    }

    // --- delete ---

    @Test
    void deletesImage() {
        Product product = sampleProduct();
        ProductImage image = new ProductImage(product.getId(), "http://img/x.png", null, 0, false);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.findByIdAndProductId(image.getId(), product.getId())).thenReturn(Optional.of(image));

        service.delete(product.getId(), image.getId());

        verify(images).delete(image);
    }

    @Test
    void deleteThrowsWhenImageMissing() {
        Product product = sampleProduct();
        UUID imageId = UUID.randomUUID();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(images.findByIdAndProductId(imageId, product.getId())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(product.getId(), imageId));
        verify(images, never()).delete(any());
    }

    @Test
    void deleteThrowsWhenProductMissing() {
        UUID productId = UUID.randomUUID();
        when(products.findById(productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(productId, UUID.randomUUID()));
    }
}
