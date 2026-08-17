package com.sumedha.commerce.product.service;

import com.sumedha.commerce.product.dto.request.CreateProductAttributeRequest;
import com.sumedha.commerce.product.dto.response.ProductAttributeResponse;
import com.sumedha.commerce.product.entity.Product;
import com.sumedha.commerce.product.entity.ProductAttribute;
import com.sumedha.commerce.product.repository.ProductAttributeRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAttributeServiceImplTest {

    @Mock
    ProductRepository products;
    @Mock
    ProductAttributeRepository attributes;

    ProductAttributeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductAttributeServiceImpl(products, attributes);
    }

    private Product sampleProduct() {
        return new Product("SKU-1", "Product One", "product-1", UUID.randomUUID(), null, BigDecimal.TEN, "USD");
    }

    @Test
    void createsAttribute() {
        Product product = sampleProduct();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(attributes.save(any(ProductAttribute.class))).thenAnswer(i -> i.getArgument(0));

        CreateProductAttributeRequest request = new CreateProductAttributeRequest("Color", "Red");
        ProductAttributeResponse response = service.create(product.getId(), request);

        assertEquals("Color", response.name());
        assertEquals("Red", response.value());
    }

    @Test
    void createThrowsWhenProductMissing() {
        UUID productId = UUID.randomUUID();
        when(products.findById(productId)).thenReturn(Optional.empty());

        CreateProductAttributeRequest request = new CreateProductAttributeRequest("Color", "Red");
        assertThrows(ResourceNotFoundException.class, () -> service.create(productId, request));
        verify(attributes, never()).save(any());
    }

    @Test
    void listReturnsAttributes() {
        Product product = sampleProduct();
        ProductAttribute attr1 = new ProductAttribute(product.getId(), "Color", "Red");
        ProductAttribute attr2 = new ProductAttribute(product.getId(), "Size", "M");
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(attributes.findByProductId(product.getId())).thenReturn(List.of(attr1, attr2));

        List<ProductAttributeResponse> result = service.list(product.getId());

        assertEquals(2, result.size());
    }

    @Test
    void listThrowsWhenProductMissing() {
        UUID productId = UUID.randomUUID();
        when(products.findById(productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.list(productId));
    }

    @Test
    void deletesAttribute() {
        Product product = sampleProduct();
        ProductAttribute attribute = new ProductAttribute(product.getId(), "Color", "Red");
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(attributes.findByIdAndProductId(attribute.getId(), product.getId())).thenReturn(Optional.of(attribute));

        service.delete(product.getId(), attribute.getId());

        verify(attributes).delete(attribute);
    }

    @Test
    void deleteThrowsWhenAttributeMissing() {
        Product product = sampleProduct();
        UUID attributeId = UUID.randomUUID();
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(attributes.findByIdAndProductId(attributeId, product.getId())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(product.getId(), attributeId));
        verify(attributes, never()).delete(any());
    }

    @Test
    void deleteThrowsWhenProductMissing() {
        UUID productId = UUID.randomUUID();
        when(products.findById(productId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(productId, UUID.randomUUID()));
    }
}
