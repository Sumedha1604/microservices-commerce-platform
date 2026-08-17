package com.sumedha.commerce.product.service;

import com.sumedha.commerce.product.dto.request.CreateCategoryRequest;
import com.sumedha.commerce.product.dto.request.UpdateCategoryRequest;
import com.sumedha.commerce.product.dto.response.CategoryResponse;
import com.sumedha.commerce.product.entity.Category;
import com.sumedha.commerce.product.repository.CategoryRepository;
import com.sumedha.commerce.product.repository.ProductRepository;
import com.sumedha.commerce.common.core.exception.BadRequestException;
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
class CategoryServiceImplTest {

    @Mock
    CategoryRepository categories;
    @Mock
    ProductRepository products;

    CategoryServiceImpl service;
    UUID categoryId;

    @BeforeEach
    void setUp() {
        service = new CategoryServiceImpl(categories, products);
        categoryId = UUID.randomUUID();
    }

    @Test
    void createsCategory() {
        when(categories.existsBySlug("electronics")).thenReturn(false);
        when(categories.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        CategoryResponse response = service.create(new CreateCategoryRequest("Electronics", "electronics", "desc", null));

        assertEquals("Electronics", response.name());
        assertEquals("electronics", response.slug());
        assertTrue(response.active());
        verify(categories).save(any(Category.class));
    }

    @Test
    void createWithParentCategory() {
        UUID parentId = UUID.randomUUID();
        when(categories.existsBySlug("phones")).thenReturn(false);
        when(categories.existsById(parentId)).thenReturn(true);
        when(categories.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        CategoryResponse response = service.create(new CreateCategoryRequest("Phones", "phones", null, parentId));

        assertEquals(parentId, response.parentCategoryId());
    }

    @Test
    void createRejectsDuplicateSlug() {
        when(categories.existsBySlug("electronics")).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.create(new CreateCategoryRequest("Electronics", "electronics", null, null)));
        verify(categories, never()).save(any());
    }

    @Test
    void createRejectsMissingParentCategory() {
        UUID parentId = UUID.randomUUID();
        when(categories.existsBySlug("phones")).thenReturn(false);
        when(categories.existsById(parentId)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> service.create(new CreateCategoryRequest("Phones", "phones", null, parentId)));
        verify(categories, never()).save(any());
    }

    @Test
    void getReturnsCategory() {
        Category category = new Category("Electronics", "electronics", "desc", null);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));

        CategoryResponse response = service.get(category.getId());

        assertEquals(category.getId(), response.categoryId());
        assertEquals("Electronics", response.name());
    }

    @Test
    void getThrowsWhenMissing() {
        when(categories.findById(categoryId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(categoryId));
    }

    @Test
    void listReturnsAllMapped() {
        Category c1 = new Category("A", "a", null, null);
        Category c2 = new Category("B", "b", null, null);
        when(categories.findAll()).thenReturn(List.of(c1, c2));

        List<CategoryResponse> result = service.list();

        assertEquals(2, result.size());
    }

    @Test
    void updatesCategory() {
        Category category = new Category("Electronics", "electronics", "desc", null);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(categories.existsBySlugAndIdNot("gadgets", category.getId())).thenReturn(false);

        CategoryResponse response = service.update(category.getId(),
                new UpdateCategoryRequest("Gadgets", "gadgets", "new desc", null, false));

        assertEquals("Gadgets", response.name());
        assertEquals("gadgets", response.slug());
        assertFalse(response.active());
    }

    @Test
    void updateRejectsDuplicateSlug() {
        Category category = new Category("Electronics", "electronics", "desc", null);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(categories.existsBySlugAndIdNot("gadgets", category.getId())).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.update(category.getId(),
                new UpdateCategoryRequest("Gadgets", "gadgets", null, null, true)));
    }

    @Test
    void updateRejectsSelfAsParent() {
        Category category = new Category("Electronics", "electronics", "desc", null);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));

        assertThrows(BadRequestException.class, () -> service.update(category.getId(),
                new UpdateCategoryRequest("Electronics", "electronics", null, category.getId(), true)));
    }

    @Test
    void updateRejectsMissingParent() {
        Category category = new Category("Electronics", "electronics", "desc", null);
        UUID missingParent = UUID.randomUUID();
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(categories.existsById(missingParent)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.update(category.getId(),
                new UpdateCategoryRequest("Electronics", "electronics", null, missingParent, true)));
    }

    @Test
    void updateThrowsWhenMissing() {
        when(categories.findById(categoryId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.update(categoryId,
                new UpdateCategoryRequest("A", "a", null, null, true)));
    }

    @Test
    void deletesCategory() {
        Category category = new Category("Electronics", "electronics", "desc", null);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(categories.existsByParentCategoryId(category.getId())).thenReturn(false);
        when(products.existsByCategoryId(category.getId())).thenReturn(false);

        service.delete(category.getId());

        verify(categories).delete(category);
    }

    @Test
    void deleteThrowsWhenMissing() {
        when(categories.findById(categoryId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(categoryId));
        verify(categories, never()).delete(any());
    }

    @Test
    void deleteBlockedByChildCategories() {
        Category category = new Category("Electronics", "electronics", "desc", null);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(categories.existsByParentCategoryId(category.getId())).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.delete(category.getId()));
        verify(categories, never()).delete(any());
    }

    @Test
    void deleteBlockedByProducts() {
        Category category = new Category("Electronics", "electronics", "desc", null);
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(categories.existsByParentCategoryId(category.getId())).thenReturn(false);
        when(products.existsByCategoryId(category.getId())).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.delete(category.getId()));
        verify(categories, never()).delete(any());
    }
}
