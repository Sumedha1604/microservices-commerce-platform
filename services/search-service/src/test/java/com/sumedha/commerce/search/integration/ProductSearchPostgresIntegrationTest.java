package com.sumedha.commerce.search.integration;

import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.search.dto.response.ProductSearchResult;
import com.sumedha.commerce.search.messaging.ProductEventParser;
import com.sumedha.commerce.search.messaging.ProductProjectionProcessor;
import com.sumedha.commerce.search.service.ProductSearchService;
import com.sumedha.commerce.search.support.ProductEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.sumedha.commerce.search.support.ProductEvents.deleted;
import static com.sumedha.commerce.search.support.ProductEvents.product;
import static com.sumedha.commerce.search.support.ProductEvents.upserted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Search behaviour against real PostgreSQL full-text and trigram indexes, over a small catalogue
 * built through the same event path production uses.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:59992",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
@Testcontainers
class ProductSearchPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("search_test")
            .withUsername("search")
            .withPassword("search");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ProductEventParser parser;
    @Autowired private ProductProjectionProcessor processor;
    @Autowired private ProductSearchService search;
    @Autowired private JdbcTemplate jdbc;

    private final UUID phones = UUID.randomUUID();
    private final UUID accessories = UUID.randomUUID();
    private final UUID computers = UUID.randomUUID();
    private final UUID acme = UUID.randomUUID();

    private ProductEvents.Product smartPhone;
    private ProductEvents.Product phoneCase;
    private ProductEvents.Product laptop;
    private ProductEvents.Product headphones;
    private ProductEvents.Product gamingPhone;
    private ProductEvents.Product draftPhone;
    private ProductEvents.Product hiddenPhone;
    private ProductEvents.Product deletedPhone;

    /** Re-seeded per test: cheap, and no test can depend on another's leftovers. */
    @BeforeEach
    void seedCatalogue() {
        jdbc.update("delete from product_search_document");
        jdbc.update("delete from processed_event");

        smartPhone = index(product().name("Smart Phone X").sku("PH-100").category(phones).brand(acme)
                .shortDescription("Our flagship").description("Flagship smartphone with a great camera").price("999.00"));
        phoneCase = index(product().name("Phone Case").sku("CASE-1").category(accessories).brand(acme)
                .description("Protective silicone case").price("19.99"));
        laptop = index(product().name("Laptop Pro 14").sku("LP-14").category(computers)
                .description("Lightweight laptop that pairs with your phone").price("1499.00"));
        headphones = index(product().name("Wireless Headphones").sku("HP-7").category(accessories)
                .shortDescription("Over-ear").description("Noise cancelling").price("199.00"));
        gamingPhone = index(product().name("Gaming Phone").sku("GP-9").category(phones)
                .description("High refresh display").price("499.00").currency("EUR"));
        draftPhone = index(product().name("Draft Phone").sku("DR-1").category(phones).status("DRAFT").price("50.00"));
        hiddenPhone = index(product().name("Hidden Phone").sku("HI-1").category(phones).active(false).price("60.00"));
        deletedPhone = index(product().name("Deleted Phone").sku("DE-1").category(phones).price("70.00").version(1));
        processor.process(parser.parse(deleted(UUID.randomUUID(), deletedPhone.productId, 2)));
    }

    private ProductEvents.Product index(ProductEvents.Product product) {
        processor.process(parser.parse(upserted(UUID.randomUUID(), product)));
        return product;
    }

    private PageResponse<ProductSearchResult> find(String q) {
        return search.search(q, null, null, null, null, null, null, null, 0, 20);
    }

    private static List<UUID> ids(PageResponse<ProductSearchResult> page) {
        return page.getItems().stream().map(ProductSearchResult::productId).toList();
    }

    private static List<String> names(PageResponse<ProductSearchResult> page) {
        return page.getItems().stream().map(ProductSearchResult::name).toList();
    }

    // ---------- matching ----------

    @Test
    void nameMatch() {
        assertEquals(List.of(laptop.productId), ids(find("laptop")));
    }

    @Test
    void descriptionMatch() {
        assertEquals(List.of(smartPhone.productId), ids(find("camera")), "'camera' appears only in a description");
        assertEquals(List.of(headphones.productId), ids(find("over-ear")), "short description is searched too");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LAPTOP", "Laptop", "lApToP", "laptop pro"})
    void matchingIsCaseInsensitive(String q) {
        assertEquals(List.of(laptop.productId), ids(find(q)));
    }

    @Test
    void partialWordsMatch() {
        assertEquals(List.of(laptop.productId), ids(find("lapt")));
        assertTrue(ids(find("headph")).contains(headphones.productId));
        assertTrue(ids(find("martph")).contains(smartPhone.productId), "infix inside 'smartphone'");
    }

    @Test
    void skuMatchesExactlyAndPartially() {
        assertEquals(List.of(laptop.productId), ids(find("lp-14")));
        assertEquals(List.of(smartPhone.productId), ids(find("PH-10")));
    }

    @Test
    void phoneMatchesNamesAndDescriptionsContainingPhone() {
        List<UUID> matches = ids(find("phone"));

        assertTrue(matches.containsAll(List.of(smartPhone.productId, phoneCase.productId, gamingPhone.productId, laptop.productId)),
                matches::toString);
        // Partial matching is substring-based, so "headphones" contains "phone" as well...
        assertTrue(matches.contains(headphones.productId), matches::toString);
        // ...but every whole-word name match ranks above that substring-only match.
        for (UUID wordMatch : List.of(smartPhone.productId, phoneCase.productId, gamingPhone.productId)) {
            assertTrue(matches.indexOf(wordMatch) < matches.indexOf(headphones.productId), matches::toString);
        }
    }

    @Test
    void noResultsIsAnEmptyPageNotAnError() {
        PageResponse<ProductSearchResult> page = find("zzzz-no-such-product");

        assertTrue(page.getItems().isEmpty());
        assertEquals(0, page.getTotalElements());
        assertEquals(0, page.getTotalPages());
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "100%", "\\", "\"", "phone -case", "the", "!!!", "' or 1=1 --"})
    void specialCharactersAndStopWordsAreSafe(String q) {
        PageResponse<ProductSearchResult> page = find(q);

        if (q.equals("%") || q.equals("_") || q.equals("100%") || q.equals("\\")) {
            assertEquals(0, page.getTotalElements(), "LIKE wildcards in the query are literal, not match-everything");
        }
    }

    // ---------- relevance ----------

    @Test
    void nameMatchesOutrankDescriptionOnlyMatches() {
        List<UUID> ranked = ids(find("phone"));

        int laptopRank = ranked.indexOf(laptop.productId);
        for (UUID nameMatch : List.of(smartPhone.productId, phoneCase.productId, gamingPhone.productId)) {
            assertTrue(ranked.indexOf(nameMatch) < laptopRank, () -> "name match must outrank description-only: " + ranked);
        }
    }

    @Test
    void anExactNameMatchRanksFirst() {
        assertEquals(phoneCase.productId, ids(find("phone case")).getFirst());
        assertEquals(smartPhone.productId, ids(find("Smart Phone X")).getFirst());
    }

    // ---------- visibility ----------

    @Test
    void draftInactiveFlaggedAndDeletedProductsAreNotReturnedByDefault() {
        List<UUID> matches = ids(find("phone"));

        assertFalse(matches.contains(draftPhone.productId));
        assertFalse(matches.contains(hiddenPhone.productId));
        assertFalse(matches.contains(deletedPhone.productId));
    }

    @Test
    void theStatusFilterSelectsAnotherStatusButNeverInactiveFlaggedProducts() {
        PageResponse<ProductSearchResult> drafts = search.search("phone", null, null, null, null, null, "DRAFT", null, 0, 20);

        assertEquals(List.of(draftPhone.productId), ids(drafts));
    }

    // ---------- filters ----------

    @Test
    void categoryAndBrandFilters() {
        assertEquals(List.of(gamingPhone.productId, smartPhone.productId).stream().sorted().toList(),
                ids(search.search("phone", phones, null, null, null, null, null, null, 0, 20)).stream().sorted().toList());
        assertEquals(List.of(phoneCase.productId),
                ids(search.search("case", accessories, null, null, null, null, null, null, 0, 20)));
        assertEquals(List.of(phoneCase.productId, smartPhone.productId).stream().sorted().toList(),
                ids(search.search(null, null, acme, null, null, null, null, null, 0, 20)).stream().sorted().toList());
    }

    @Test
    void priceRangeFilterIsInclusive() {
        PageResponse<ProductSearchResult> page = search.search("phone", null, null, new BigDecimal("19.99"),
                new BigDecimal("999.00"), null, null, "priceAsc", 0, 20);

        assertEquals(List.of("Phone Case", "Wireless Headphones", "Gaming Phone", "Smart Phone X"), names(page));
    }

    @Test
    void currencyFilter() {
        assertEquals(List.of(gamingPhone.productId),
                ids(search.search("phone", null, null, null, null, "eur", null, null, 0, 20)));
    }

    // ---------- pagination ----------

    @Test
    void pagesAreStableDisjointAndCounted() {
        PageResponse<ProductSearchResult> first = search.search("phone", null, null, null, null, null, null, null, 0, 2);
        PageResponse<ProductSearchResult> second = search.search("phone", null, null, null, null, null, null, null, 1, 2);
        PageResponse<ProductSearchResult> third = search.search("phone", null, null, null, null, null, null, null, 2, 2);
        PageResponse<ProductSearchResult> beyond = search.search("phone", null, null, null, null, null, null, null, 5, 2);

        assertEquals(5, first.getTotalElements());
        assertEquals(3, first.getTotalPages());
        assertTrue(first.isHasNext());
        assertEquals(2, first.getItems().size());
        assertEquals(2, second.getItems().size());
        assertTrue(second.isHasNext());
        assertEquals(1, third.getItems().size());
        assertFalse(third.isHasNext());
        List<UUID> all = new ArrayList<>(ids(first));
        all.addAll(ids(second));
        all.addAll(ids(third));
        assertEquals(5, all.stream().distinct().count(), "no product appears on two pages");
        assertEquals(ids(find("phone")), all, "pages concatenate to the full ranking");
        assertTrue(beyond.getItems().isEmpty());
        assertEquals(5, beyond.getTotalElements());
    }

    // ---------- sorting ----------

    @Test
    void sortModes() {
        assertEquals(List.of("Phone Case", "Wireless Headphones", "Gaming Phone", "Smart Phone X", "Laptop Pro 14"),
                names(search.search(null, null, null, null, null, null, null, "priceAsc", 0, 20)));
        assertEquals(List.of("Laptop Pro 14", "Smart Phone X", "Gaming Phone", "Wireless Headphones", "Phone Case"),
                names(search.search(null, null, null, null, null, null, null, "priceDesc", 0, 20)));
        assertEquals(List.of("Gaming Phone", "Laptop Pro 14", "Phone Case", "Smart Phone X", "Wireless Headphones"),
                names(search.search(null, null, null, null, null, null, null, "nameAsc", 0, 20)));
        assertEquals(List.of("Wireless Headphones", "Smart Phone X", "Phone Case", "Laptop Pro 14", "Gaming Phone"),
                names(search.search(null, null, null, null, null, null, null, "nameDesc", 0, 20)));
        assertEquals(names(search.search(null, null, null, null, null, null, null, "nameAsc", 0, 20)),
                names(search.search(null, null, null, null, null, null, null, null, 0, 20)),
                "browsing without a query falls back to name order");
    }
}
