package com.sumedha.commerce.product.service;

import com.sumedha.commerce.product.dto.request.CreateBrandRequest;
import com.sumedha.commerce.product.dto.request.UpdateBrandRequest;
import com.sumedha.commerce.product.dto.response.BrandResponse;
import com.sumedha.commerce.product.entity.Brand;
import com.sumedha.commerce.product.repository.BrandRepository;
import com.sumedha.commerce.product.repository.ProductRepository;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandServiceImplTest {

    @Mock
    BrandRepository brands;
    @Mock
    ProductRepository products;

    BrandServiceImpl service;
    UUID brandId;

    @BeforeEach
    void setUp() {
        service = new BrandServiceImpl(brands, products);
        brandId = UUID.randomUUID();
    }

    @Test
    void createsBrand() {
        when(brands.existsBySlug("acme")).thenReturn(false);
        when(brands.save(any(Brand.class))).thenAnswer(i -> i.getArgument(0));

        BrandResponse response = service.create(new CreateBrandRequest("Acme", "acme", "desc"));

        assertEquals("Acme", response.name());
        assertEquals("acme", response.slug());
        assertTrue(response.active());
        verify(brands).save(any(Brand.class));
    }

    @Test
    void createRejectsDuplicateSlug() {
        when(brands.existsBySlug("acme")).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.create(new CreateBrandRequest("Acme", "acme", null)));
        verify(brands, never()).save(any());
    }

    @Test
    void getReturnsBrand() {
        Brand brand = new Brand("Acme", "acme", "desc");
        when(brands.findById(brand.getId())).thenReturn(Optional.of(brand));

        BrandResponse response = service.get(brand.getId());

        assertEquals(brand.getId(), response.brandId());
        assertEquals("Acme", response.name());
    }

    @Test
    void getThrowsWhenMissing() {
        when(brands.findById(brandId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(brandId));
    }

    @Test
    void listReturnsAllMapped() {
        Brand b1 = new Brand("A", "a", null);
        Brand b2 = new Brand("B", "b", null);
        when(brands.findAll()).thenReturn(List.of(b1, b2));

        List<BrandResponse> result = service.list();

        assertEquals(2, result.size());
    }

    @Test
    void updatesBrand() {
        Brand brand = new Brand("Acme", "acme", "desc");
        when(brands.findById(brand.getId())).thenReturn(Optional.of(brand));
        when(brands.existsBySlugAndIdNot("acme-updated", brand.getId())).thenReturn(false);

        BrandResponse response = service.update(brand.getId(),
                new UpdateBrandRequest("Acme Updated", "acme-updated", "new desc", false));

        assertEquals("Acme Updated", response.name());
        assertEquals("acme-updated", response.slug());
        assertFalse(response.active());
    }

    @Test
    void updateRejectsDuplicateSlug() {
        Brand brand = new Brand("Acme", "acme", "desc");
        when(brands.findById(brand.getId())).thenReturn(Optional.of(brand));
        when(brands.existsBySlugAndIdNot("acme-updated", brand.getId())).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.update(brand.getId(),
                new UpdateBrandRequest("Acme Updated", "acme-updated", null, true)));
    }

    @Test
    void updateThrowsWhenMissing() {
        when(brands.findById(brandId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.update(brandId,
                new UpdateBrandRequest("A", "a", null, true)));
    }

    @Test
    void deletesBrand() {
        Brand brand = new Brand("Acme", "acme", "desc");
        when(brands.findById(brand.getId())).thenReturn(Optional.of(brand));
        when(products.existsByBrandId(brand.getId())).thenReturn(false);

        service.delete(brand.getId());

        verify(brands).delete(brand);
    }

    @Test
    void deleteThrowsWhenMissing() {
        when(brands.findById(brandId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(brandId));
        verify(brands, never()).delete(any());
    }

    @Test
    void deleteBlockedByProducts() {
        Brand brand = new Brand("Acme", "acme", "desc");
        when(brands.findById(brand.getId())).thenReturn(Optional.of(brand));
        when(products.existsByBrandId(brand.getId())).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.delete(brand.getId()));
        verify(brands, never()).delete(any());
    }
}
