package com.sumedha.commerce.product.integration;

import com.sumedha.commerce.common.core.enums.SortDirection;
import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.ConflictException;
import com.sumedha.commerce.common.core.pagination.PageRequest;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.product.dto.request.CreateBrandRequest;
import com.sumedha.commerce.product.dto.request.CreateCategoryRequest;
import com.sumedha.commerce.product.dto.request.CreateProductAttributeRequest;
import com.sumedha.commerce.product.dto.request.CreateProductImageRequest;
import com.sumedha.commerce.product.dto.request.CreateProductRequest;
import com.sumedha.commerce.product.dto.request.ProductFilter;
import com.sumedha.commerce.product.dto.request.UpdateProductRequest;
import com.sumedha.commerce.product.dto.response.BrandResponse;
import com.sumedha.commerce.product.dto.response.CategoryResponse;
import com.sumedha.commerce.product.dto.response.ProductResponse;
import com.sumedha.commerce.product.dto.response.ProductSummaryResponse;
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
import com.sumedha.commerce.product.service.BrandService;
import com.sumedha.commerce.product.service.CategoryService;
import com.sumedha.commerce.product.service.ProductAttributeService;
import com.sumedha.commerce.product.service.ProductImageService;
import com.sumedha.commerce.product.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class ProductPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("product_test")
            .withUsername("product")
            .withPassword("product");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private CategoryService categoryService;
    @Autowired private BrandService brandService;
    @Autowired private ProductService productService;
    @Autowired private ProductImageService imageService;
    @Autowired private ProductAttributeService attributeService;

    @Autowired private CategoryRepository categories;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private ProductImageRepository images;
    @Autowired private ProductAttributeRepository attributes;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearDatabase() {
        images.deleteAll();
        attributes.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        brands.deleteAll();
    }

    // ---------- Flyway / schema ----------

    @Test
    void flywayCreatesTheProductSchema() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertTrue(tables.containsAll(
                List.of("categories", "brands", "products", "product_images", "product_attributes")));
    }

    // ---------- Category ----------

    @Test
    void categoryRepositoryPersistsReadsAndEnforcesSlugUniqueness() {
        CategoryResponse category = categoryService.create(new CreateCategoryRequest("Electronics", "electronics", "desc", null));
        assertEquals(category.categoryId(), categories.findBySlug("electronics").orElseThrow().getId());
        assertTrue(categories.existsBySlug("electronics"));
        assertFalse(categories.existsBySlug("nonexistent-slug"));

        assertThrows(DataIntegrityViolationException.class,
                () -> categories.saveAndFlush(new Category("Duplicate", "electronics", null, null)));
    }

    @Test
    void categoryForeignKeyConstraintsAreEnforced() {
        assertThrows(DataIntegrityViolationException.class,
                () -> categories.saveAndFlush(new Category("Orphan", "orphan-cat", null, UUID.randomUUID())));
    }

    // ---------- Brand ----------

    @Test
    void brandRepositoryPersistsReadsAndEnforcesSlugUniqueness() {
        BrandResponse brand = brandService.create(new CreateBrandRequest("Acme", "acme", "desc"));
        assertEquals(brand.brandId(), brands.findBySlug("acme").orElseThrow().getId());
        assertTrue(brands.existsBySlug("acme"));
        assertFalse(brands.existsBySlug("nonexistent-slug"));

        assertThrows(DataIntegrityViolationException.class,
                () -> brands.saveAndFlush(new com.sumedha.commerce.product.entity.Brand("Duplicate", "acme", null)));
    }

    // ---------- Product ----------

    @Test
    void productRepositoryPersistsRelationshipsAndConstraints() {
        UUID categoryId = createCategory("electronics").categoryId();
        UUID brandId = createBrand("acme").brandId();

        ProductResponse created = productService.create(new CreateProductRequest(
                "SKU-100", "Wireless Mouse", "wireless-mouse", categoryId, brandId,
                new BigDecimal("19.9900"), "USD"));

        Product persisted = products.findById(created.productId()).orElseThrow();
        assertEquals(categoryId, persisted.getCategoryId());
        assertEquals(brandId, persisted.getBrandId());
        assertEquals(ProductStatus.DRAFT, persisted.getStatus());
        assertEquals(0, new BigDecimal("19.9900").compareTo(persisted.getPrice()));
        assertEquals("USD", persisted.getCurrency());

        // ProductStatus persists to the database as its enum string name, not an ordinal.
        assertEquals("DRAFT", jdbc.queryForObject(
                "select status from products where product_id = ?", String.class, created.productId()));

        productService.update(created.productId(), new UpdateProductRequest(
                "Wireless Mouse", "wireless-mouse", null, null, categoryId, brandId,
                new BigDecimal("19.9900"), "USD", ProductStatus.ACTIVE, true));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "select status from products where product_id = ?", String.class, created.productId()));

        // lookup methods
        assertEquals(created.productId(), products.findBySku("SKU-100").orElseThrow().getId());
        assertEquals(created.productId(), products.findBySlug("wireless-mouse").orElseThrow().getId());
        assertTrue(products.existsBySku("SKU-100"));
        assertTrue(products.existsBySlug("wireless-mouse"));
        assertTrue(products.existsByCategoryId(categoryId));
        assertTrue(products.existsByBrandId(brandId));
        assertFalse(products.existsBySlugAndIdNot("wireless-mouse", created.productId()));
        assertFalse(products.existsByCategoryId(UUID.randomUUID()));

        // SKU / slug uniqueness enforced at the application layer (ConflictException) before hitting the DB.
        assertThrows(ConflictException.class, () -> productService.create(new CreateProductRequest(
                "SKU-100", "Another Mouse", "another-mouse", categoryId, brandId,
                BigDecimal.TEN, "USD")));
        assertThrows(ConflictException.class, () -> productService.create(new CreateProductRequest(
                "SKU-101", "Wireless Mouse", "wireless-mouse", categoryId, brandId,
                BigDecimal.TEN, "USD")));

        // and enforced at the DB layer too (unique constraints on sku/slug).
        assertThrows(DataIntegrityViolationException.class, () -> products.saveAndFlush(
                new Product("SKU-100", "Dup", "dup-slug", categoryId, brandId, BigDecimal.ONE, "USD")));
        assertThrows(DataIntegrityViolationException.class, () -> products.saveAndFlush(
                new Product("SKU-999", "Dup", "wireless-mouse", categoryId, brandId, BigDecimal.ONE, "USD")));
    }

    @Test
    void productPriceCheckConstraintRejectsNegativeValues() {
        UUID categoryId = createCategory("electronics").categoryId();
        assertThrows(DataIntegrityViolationException.class, () -> products.saveAndFlush(
                new Product("SKU-NEG", "Negative", "negative", categoryId, null, new BigDecimal("-1.00"), "USD")));
    }

    @Test
    void productForeignKeyConstraintsAreEnforced() {
        UUID categoryId = createCategory("electronics").categoryId();

        assertThrows(DataIntegrityViolationException.class, () -> products.saveAndFlush(
                new Product("SKU-BADCAT", "Bad Category", "bad-category", UUID.randomUUID(), null, BigDecimal.TEN, "USD")));
        assertThrows(DataIntegrityViolationException.class, () -> products.saveAndFlush(
                new Product("SKU-BADBRAND", "Bad Brand", "bad-brand", categoryId, UUID.randomUUID(), BigDecimal.TEN, "USD")));
        assertThrows(DataIntegrityViolationException.class, () -> products.saveAndFlush(
                new Product("SKU-NOCAT", "No Category", "no-category", null, null, BigDecimal.TEN, "USD")));
    }

    // ---------- ProductImage ----------

    @Test
    void productImageRepositoryPersistsRelationshipAndOrdering() {
        UUID categoryId = createCategory("electronics").categoryId();
        UUID productId = createProduct("SKU-IMG", "img-product", categoryId, null, "10.00").productId();

        assertFalse(images.existsByProductId(productId));

        var second = imageService.create(productId, new CreateProductImageRequest("http://img/2.png", "second", 2, false));
        var first = imageService.create(productId, new CreateProductImageRequest("http://img/1.png", "first", 1, true));

        assertTrue(images.existsByProductId(productId));
        List<ProductImage> ordered = images.findByProductIdOrderBySortOrderAsc(productId);
        assertEquals(2, ordered.size());
        assertEquals(first.imageId(), ordered.get(0).getId());
        assertEquals(second.imageId(), ordered.get(1).getId());

        assertEquals(first.imageId(), images.findByIdAndProductId(first.imageId(), productId).orElseThrow().getId());
        assertEquals(first.imageId(), images.findByProductIdAndPrimaryImageTrue(productId).orElseThrow().getId());

        assertThrows(DataIntegrityViolationException.class, () -> images.saveAndFlush(
                new ProductImage(UUID.randomUUID(), "http://orphan.png", null, 0, false)));
    }

    // ---------- ProductAttribute ----------

    @Test
    void productAttributeRepositoryPersistsRelationshipAndLookups() {
        UUID categoryId = createCategory("electronics").categoryId();
        UUID productId = createProduct("SKU-ATTR", "attr-product", categoryId, null, "10.00").productId();

        assertFalse(attributes.existsByProductId(productId));

        var attr = attributeService.create(productId, new CreateProductAttributeRequest("color", "black"));

        assertTrue(attributes.existsByProductId(productId));
        List<ProductAttribute> found = attributes.findByProductId(productId);
        assertEquals(1, found.size());
        assertEquals("color", found.get(0).getName());
        assertEquals(attr.attributeId(), attributes.findByIdAndProductId(attr.attributeId(), productId).orElseThrow().getId());

        assertThrows(DataIntegrityViolationException.class, () -> attributes.saveAndFlush(
                new ProductAttribute(UUID.randomUUID(), "orphan", "value")));
    }

    // ---------- Delete safety ----------

    @Test
    void productWithNoImagesOrAttributesCanBeDeleted() {
        UUID categoryId = createCategory("electronics").categoryId();
        UUID productId = createProduct("SKU-DEL-1", "del-product-1", categoryId, null, "10.00").productId();

        productService.delete(productId);

        assertFalse(products.existsById(productId));
    }

    @Test
    void productWithImagesIsProtectedFromDeletion() {
        UUID categoryId = createCategory("electronics").categoryId();
        UUID productId = createProduct("SKU-DEL-2", "del-product-2", categoryId, null, "10.00").productId();
        imageService.create(productId, new CreateProductImageRequest("http://img/1.png", null, 0, true));

        // application-level guard
        assertThrows(ConflictException.class, () -> productService.delete(productId));
        assertTrue(products.existsById(productId));

        // no ON DELETE CASCADE exists: a raw repository delete is rejected by the FK constraint too.
        Product product = products.findById(productId).orElseThrow();
        assertThrows(DataIntegrityViolationException.class, () -> {
            products.delete(product);
            products.flush();
        });
    }

    @Test
    void productWithAttributesIsProtectedFromDeletion() {
        UUID categoryId = createCategory("electronics").categoryId();
        UUID productId = createProduct("SKU-DEL-3", "del-product-3", categoryId, null, "10.00").productId();
        attributeService.create(productId, new CreateProductAttributeRequest("color", "red"));

        assertThrows(ConflictException.class, () -> productService.delete(productId));
        assertTrue(products.existsById(productId));

        Product product = products.findById(productId).orElseThrow();
        assertThrows(DataIntegrityViolationException.class, () -> {
            products.delete(product);
            products.flush();
        });
    }

    // ---------- Catalogue search (ProductSpecifications / ProductService.search) ----------

    @Test
    void searchWithNoFiltersReturnsEverything() {
        seedCatalogue();
        ProductFilter filter = new ProductFilter(null, null, null, null, null, null);
        PageResponse<ProductSummaryResponse> result = productService.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "name"));
        assertEquals(5, result.getTotalElements());
    }

    @Test
    void searchFiltersBySearchTextCaseInsensitively() {
        seedCatalogue();
        ProductFilter filter = new ProductFilter("BOOK", null, null, null, null, null);
        PageResponse<ProductSummaryResponse> result = productService.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "name"));
        assertEquals(2, result.getTotalElements());
        assertTrue(result.getItems().stream().allMatch(p -> p.name().toLowerCase().contains("book")));
    }

    @Test
    void searchFiltersByCategory() {
        Catalogue catalogue = seedCatalogue();
        ProductFilter filter = new ProductFilter(null, catalogue.electronicsId, null, null, null, null);
        PageResponse<ProductSummaryResponse> result = productService.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "name"));
        assertEquals(3, result.getTotalElements());
    }

    @Test
    void searchFiltersByBrand() {
        Catalogue catalogue = seedCatalogue();
        ProductFilter filter = new ProductFilter(null, null, catalogue.globexId, null, null, null);
        PageResponse<ProductSummaryResponse> result = productService.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "name"));
        assertEquals(2, result.getTotalElements());
    }

    @Test
    void searchFiltersByStatus() {
        seedCatalogue();
        ProductFilter filter = new ProductFilter(null, null, null, ProductStatus.ACTIVE, null, null);
        PageResponse<ProductSummaryResponse> result = productService.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "name"));
        assertEquals(2, result.getTotalElements());
    }

    @Test
    void searchFiltersByPriceRangeInclusiveOfBoundaries() {
        seedCatalogue();

        var min = productService.search(new ProductFilter(null, null, null, null, new BigDecimal("45.00"), null),
                PageRequest.of(0, 10, SortDirection.ASC, "price"));
        assertEquals(2, min.getTotalElements());
        assertEquals(0, new BigDecimal("45.00").compareTo(min.getItems().get(0).price()));

        var max = productService.search(new ProductFilter(null, null, null, null, null, new BigDecimal("20.00")),
                PageRequest.of(0, 10, SortDirection.ASC, "price"));
        assertEquals(2, max.getTotalElements());

        var range = productService.search(new ProductFilter(null, null, null, null, new BigDecimal("20.00"), new BigDecimal("50.00")),
                PageRequest.of(0, 10, SortDirection.ASC, "price"));
        assertEquals(2, range.getTotalElements());
    }

    @Test
    void searchCombinesMultipleFilters() {
        Catalogue catalogue = seedCatalogue();
        ProductFilter filter = new ProductFilter(null, catalogue.electronicsId, null, ProductStatus.ACTIVE, null, null);
        PageResponse<ProductSummaryResponse> result = productService.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "name"));
        assertEquals(1, result.getTotalElements());
        assertEquals("Mechanical Keyboard", result.getItems().get(0).name());
    }

    @Test
    void searchPaginatesResultsInSortOrder() {
        seedCatalogue();
        ProductFilter filter = new ProductFilter(null, null, null, null, null, null);

        var page0 = productService.search(filter, PageRequest.of(0, 2, SortDirection.ASC, "name"));
        assertEquals(3, page0.getTotalPages());
        assertTrue(page0.isHasNext());
        assertFalse(page0.isHasPrevious());
        assertEquals(List.of("Bluetooth Speaker", "Cooking Book"), names(page0));

        var page1 = productService.search(filter, PageRequest.of(1, 2, SortDirection.ASC, "name"));
        assertTrue(page1.isHasNext());
        assertTrue(page1.isHasPrevious());
        assertEquals(List.of("Mechanical Keyboard", "Programming Book"), names(page1));

        var page2 = productService.search(filter, PageRequest.of(2, 2, SortDirection.ASC, "name"));
        assertFalse(page2.isHasNext());
        assertTrue(page2.isHasPrevious());
        assertEquals(List.of("Wireless Mouse"), names(page2));
    }

    @Test
    void searchSortsByAllowedFields() {
        seedCatalogue();
        ProductFilter filter = new ProductFilter(null, null, null, null, null, null);

        var byPriceAsc = productService.search(filter, PageRequest.of(0, 10, SortDirection.ASC, "price"));
        assertEquals(List.of("Cooking Book", "Wireless Mouse", "Programming Book", "Bluetooth Speaker", "Mechanical Keyboard"),
                names(byPriceAsc));

        var byPriceDesc = productService.search(filter, PageRequest.of(0, 10, SortDirection.DESC, "price"));
        assertEquals(List.of("Mechanical Keyboard", "Bluetooth Speaker", "Programming Book", "Wireless Mouse", "Cooking Book"),
                names(byPriceDesc));
    }

    @Test
    void searchRejectsDisallowedSortFieldsAndInvertedPriceRange() {
        seedCatalogue();
        ProductFilter noFilter = new ProductFilter(null, null, null, null, null, null);
        assertThrows(BadRequestException.class,
                () -> productService.search(noFilter, PageRequest.of(0, 10, SortDirection.ASC, "sku")));

        ProductFilter invertedRange = new ProductFilter(null, null, null, null, new BigDecimal("50"), new BigDecimal("10"));
        assertThrows(BadRequestException.class,
                () -> productService.search(invertedRange, PageRequest.of(0, 10, SortDirection.ASC, "name")));
    }

    // ---------- helpers ----------

    private CategoryResponse createCategory(String slug) {
        return categoryService.create(new CreateCategoryRequest("Category " + slug, slug, null, null));
    }

    private BrandResponse createBrand(String slug) {
        return brandService.create(new CreateBrandRequest("Brand " + slug, slug, null));
    }

    private ProductResponse createProduct(String sku, String slug, UUID categoryId, UUID brandId, String price) {
        return createNamedProduct(sku, "Product " + slug, slug, categoryId, brandId, price);
    }

    private ProductResponse createNamedProduct(String sku, String name, String slug, UUID categoryId, UUID brandId, String price) {
        return productService.create(new CreateProductRequest(sku, name, slug, categoryId, brandId, new BigDecimal(price), "USD"));
    }

    private static List<String> names(PageResponse<ProductSummaryResponse> response) {
        return response.getItems().stream().map(ProductSummaryResponse::name).toList();
    }

    private static final class Catalogue {
        UUID electronicsId;
        UUID booksId;
        UUID acmeId;
        UUID globexId;
    }

    private Catalogue seedCatalogue() {
        Catalogue catalogue = new Catalogue();
        catalogue.electronicsId = createCategory("electronics").categoryId();
        catalogue.booksId = createCategory("books").categoryId();
        catalogue.acmeId = createBrand("acme").brandId();
        catalogue.globexId = createBrand("globex").brandId();

        createNamedProduct("SKU-1", "Wireless Mouse", "wireless-mouse", catalogue.electronicsId, catalogue.acmeId, "19.99");

        ProductResponse keyboard = createNamedProduct("SKU-2", "Mechanical Keyboard", "mechanical-keyboard", catalogue.electronicsId, catalogue.acmeId, "89.50");
        productService.update(keyboard.productId(), new UpdateProductRequest(
                keyboard.name(), keyboard.slug(), null, null, catalogue.electronicsId, catalogue.acmeId,
                keyboard.price(), keyboard.currency(), ProductStatus.ACTIVE, true));

        ProductResponse speaker = createNamedProduct("SKU-3", "Bluetooth Speaker", "bluetooth-speaker", catalogue.electronicsId, catalogue.globexId, "45.00");
        productService.update(speaker.productId(), new UpdateProductRequest(
                speaker.name(), speaker.slug(), null, null, catalogue.electronicsId, catalogue.globexId,
                speaker.price(), speaker.currency(), ProductStatus.INACTIVE, true));

        ProductResponse book = createNamedProduct("SKU-4", "Programming Book", "programming-book", catalogue.booksId, catalogue.globexId, "29.99");
        productService.update(book.productId(), new UpdateProductRequest(
                book.name(), book.slug(), null, null, catalogue.booksId, catalogue.globexId,
                book.price(), book.currency(), ProductStatus.ACTIVE, true));

        ProductResponse cookbook = createNamedProduct("SKU-5", "Cooking Book", "cooking-book", catalogue.booksId, null, "15.00");
        productService.update(cookbook.productId(), new UpdateProductRequest(
                cookbook.name(), cookbook.slug(), null, null, catalogue.booksId, null,
                cookbook.price(), cookbook.currency(), ProductStatus.DISCONTINUED, true));

        return catalogue;
    }
}
