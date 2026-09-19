package com.sumedha.commerce.recommendation.service;

import com.sumedha.commerce.recommendation.dto.response.ProductRecommendation;
import com.sumedha.commerce.recommendation.service.RecommendationScoring.Reason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recommendation rules, one at a time, on the Java statement of the algorithm. */
class RelatedProductRankerTest {

    private final UUID phones = UUID.randomUUID();
    private final UUID laptops = UUID.randomUUID();
    private final UUID acme = UUID.randomUUID();
    private final UUID globex = UUID.randomUUID();

    private RecommendationCandidate product(String name, UUID category, UUID brand, String price, String currency) {
        return new RecommendationCandidate(UUID.randomUUID(), name, name.toLowerCase().replace(' ', '-'), category, brand,
                new BigDecimal(price), currency, "ACTIVE", true, false);
    }

    private RecommendationCandidate withId(String id, String name, UUID category) {
        return new RecommendationCandidate(UUID.fromString(id), name, "slug", category, null, new BigDecimal("999"),
                "USD", "ACTIVE", true, false);
    }

    private final RecommendationCandidate source = product("Source Phone", phones, acme, "100.00", "USD");

    private List<UUID> ids(List<ProductRecommendation> ranked) {
        return ranked.stream().map(ProductRecommendation::productId).toList();
    }

    @Test
    void sameCategoryOutranksBrandOnlyAndUnrelatedProductsAreOmitted() {
        RecommendationCandidate category = product("Category Phone", phones, globex, "900.00", "USD");
        RecommendationCandidate brandOnly = product("Brand Laptop", laptops, acme, "900.00", "USD");
        RecommendationCandidate unrelated = product("Unrelated Laptop", laptops, globex, "100.00", "USD");

        List<ProductRecommendation> ranked = RelatedProductRanker.rank(source, List.of(unrelated, brandOnly, category), 10);

        assertEquals(List.of(category.productId(), brandOnly.productId()), ids(ranked));
        assertFalse(ids(ranked).contains(unrelated.productId()), "price similarity alone never makes a product related");
    }

    @Test
    void sameCategoryAndBrandOutranksCategoryOnly() {
        RecommendationCandidate categoryOnly = product("Aaa Category Only", phones, globex, "900.00", "USD");
        RecommendationCandidate categoryAndBrand = product("Zzz Category And Brand", phones, acme, "900.00", "USD");

        List<ProductRecommendation> ranked = RelatedProductRanker.rank(source, List.of(categoryOnly, categoryAndBrand), 10);

        assertEquals(List.of(categoryAndBrand.productId(), categoryOnly.productId()), ids(ranked));
        assertEquals(7, ranked.get(0).score());
        assertEquals(List.of(Reason.SAME_CATEGORY, Reason.SAME_BRAND), ranked.get(0).reasons());
        assertEquals(5, ranked.get(1).score());
    }

    @Test
    void categoryAloneOutranksBrandPlusPrice() {
        RecommendationCandidate categoryFarPrice = product("Category Far Price", phones, null, "500.00", "USD");
        RecommendationCandidate brandClosePrice = product("Brand Close Price", laptops, acme, "101.00", "USD");

        List<ProductRecommendation> ranked = RelatedProductRanker.rank(source, List.of(brandClosePrice, categoryFarPrice), 10);

        assertEquals(List.of(categoryFarPrice.productId(), brandClosePrice.productId()), ids(ranked));
        assertEquals(5, ranked.get(0).score());
        assertEquals(3, ranked.get(1).score());
        assertEquals(List.of(Reason.SAME_BRAND, Reason.SIMILAR_PRICE), ranked.get(1).reasons());
    }

    @Test
    void priceSimilarityBreaksOtherwiseEqualScores() {
        RecommendationCandidate far = product("Aaa Far", phones, globex, "130.00", "USD");
        RecommendationCandidate close = product("Zzz Close", phones, globex, "120.00", "USD");

        List<ProductRecommendation> ranked = RelatedProductRanker.rank(source, List.of(far, close), 10);

        assertEquals(List.of(close.productId(), far.productId()), ids(ranked));
        assertEquals(List.of(Reason.SAME_CATEGORY, Reason.SIMILAR_PRICE), ranked.get(0).reasons());
    }

    @Test
    void thePriceBandIsInclusiveAtTwentyPercent() {
        assertTrue(RecommendationScoring.similarPrice(source, product("Low Edge", phones, null, "80.00", "USD")));
        assertTrue(RecommendationScoring.similarPrice(source, product("High Edge", phones, null, "120.00", "USD")));
        assertFalse(RecommendationScoring.similarPrice(source, product("Just Over", phones, null, "120.01", "USD")));
        assertFalse(RecommendationScoring.similarPrice(source, product("Just Under", phones, null, "79.99", "USD")));
    }

    @Test
    void thePriceBonusRequiresTheSameCurrency() {
        RecommendationCandidate euro = product("Euro Phone", phones, globex, "100.00", "EUR");
        RecommendationCandidate lowerCaseUsd = product("Usd Phone", phones, globex, "100.00", "usd");

        assertFalse(RecommendationScoring.similarPrice(source, euro), "no FX conversion: different currency, no bonus");
        assertTrue(RecommendationScoring.similarPrice(source, lowerCaseUsd), "currency codes compare case-insensitively");
        List<ProductRecommendation> ranked = RelatedProductRanker.rank(source, List.of(euro), 10);
        assertEquals(5, ranked.getFirst().score());
        assertEquals(List.of(Reason.SAME_CATEGORY), ranked.getFirst().reasons());
    }

    @Test
    void aFreeSourceOnlyMatchesFreeCandidatesOnPrice() {
        RecommendationCandidate free = product("Free Source", phones, null, "0.00", "USD");

        assertTrue(RecommendationScoring.similarPrice(free, product("Also Free", phones, null, "0.00", "USD")));
        assertFalse(RecommendationScoring.similarPrice(free, product("One Cent", phones, null, "0.01", "USD")));
    }

    @Test
    void aSourceWithoutABrandMatchesNoCandidateOnBrand() {
        RecommendationCandidate noBrand = product("No Brand Source", phones, null, "100.00", "USD");
        RecommendationCandidate alsoNoBrand = product("Also No Brand", laptops, null, "100.00", "USD");

        assertFalse(RecommendationScoring.sameBrand(noBrand, alsoNoBrand), "two missing brands are not a shared brand");
        assertTrue(RelatedProductRanker.rank(noBrand, List.of(alsoNoBrand), 10).isEmpty());
    }

    @Test
    void theSourceIsNeverRecommendedToItself() {
        RecommendationCandidate sibling = product("Sibling", phones, acme, "100.00", "USD");

        assertEquals(List.of(sibling.productId()), ids(RelatedProductRanker.rank(source, List.of(source, sibling), 10)));
    }

    @Test
    void inactiveNonActiveAndDeletedProductsAreNeverRecommended() {
        UUID id = UUID.randomUUID();
        List<RecommendationCandidate> excluded = List.of(
                new RecommendationCandidate(UUID.randomUUID(), "Inactive", "s", phones, acme, BigDecimal.TEN, "USD", "ACTIVE", false, false),
                new RecommendationCandidate(UUID.randomUUID(), "Draft", "s", phones, acme, BigDecimal.TEN, "USD", "DRAFT", true, false),
                new RecommendationCandidate(UUID.randomUUID(), "Discontinued", "s", phones, acme, BigDecimal.TEN, "USD", "DISCONTINUED", true, false),
                new RecommendationCandidate(id, "Deleted", "s", phones, acme, BigDecimal.TEN, "USD", "ACTIVE", true, true));

        assertTrue(RelatedProductRanker.rank(source, excluded, 10).isEmpty());
    }

    @Test
    void equalScoresAreOrderedByNameThenProductIdRegardlessOfInputOrder() {
        RecommendationCandidate bravo = withId("00000000-0000-0000-0000-000000000009", "Bravo", phones);
        RecommendationCandidate alphaHighId = withId("ffffffff-0000-0000-0000-000000000000", "Alpha", phones);
        RecommendationCandidate alphaLowId = withId("0fffffff-0000-0000-0000-000000000000", "Alpha", phones);
        RecommendationCandidate upperCase = withId("00000000-0000-0000-0000-000000000001", "Zulu", phones);
        List<RecommendationCandidate> catalogue = new ArrayList<>(List.of(bravo, alphaHighId, alphaLowId, upperCase));
        List<UUID> expected = List.of(alphaLowId.productId(), alphaHighId.productId(), bravo.productId(), upperCase.productId());

        Random random = new Random(42);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(catalogue, random);
            assertEquals(expected, ids(RelatedProductRanker.rank(source, catalogue, 10)));
        }
    }

    /** "ffffffff-…" is a negative long; a signed UUID comparison would sort it first. The string order does not. */
    @Test
    void productIdTieBreakUsesTheCanonicalStringOrder() {
        RecommendationCandidate negativeMsb = withId("ffffffff-ffff-ffff-ffff-ffffffffffff", "Same", phones);
        RecommendationCandidate positiveMsb = withId("7fffffff-ffff-ffff-ffff-ffffffffffff", "Same", phones);

        assertEquals(List.of(positiveMsb.productId(), negativeMsb.productId()),
                ids(RelatedProductRanker.rank(source, List.of(negativeMsb, positiveMsb), 10)));
    }

    @Test
    void theLimitIsApplied() {
        List<RecommendationCandidate> catalogue = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            catalogue.add(product("Phone " + (char) ('A' + i % 26) + i, phones, null, "999.00", "USD"));
        }

        assertEquals(5, RelatedProductRanker.rank(source, catalogue, 5).size());
        assertEquals(30, RelatedProductRanker.rank(source, catalogue, 50).size());
    }
}
