package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.bench.dataset.ProductLikeDataset;
import com.naqqa.elasticsearch.test.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class ProductLikeDatasetTest {

    private static final int SAMPLE_SIZE = 2_000;

    @Test
    public void sameSeedProducesIdenticalProducts() {
        ProductLikeDataset a = new ProductLikeDataset(42L);
        ProductLikeDataset b = new ProductLikeDataset(42L);
        for (long i = 0; i < SAMPLE_SIZE; i++) {
            assertEquals(a.product(i), b.product(i));
        }
    }

    @Test
    public void differentSeedProducesDifferentProducts() {
        ProductLikeDataset a = new ProductLikeDataset(1L);
        ProductLikeDataset b = new ProductLikeDataset(2L);
        boolean anyDifferent = false;
        for (long i = 0; i < 500; i++) {
            if (!a.product(i).name().equals(b.product(i).name())) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "expected different seeds to diverge");
    }

    @Test
    public void isOrderIndependentAndRepeatable() {
        ProductLikeDataset dataset = new ProductLikeDataset(7L);
        ProductLikeDataset.ProductDoc firstPass = dataset.product(777);
        for (long i = 0; i < 1_000; i++) {
            dataset.product(i);
        }
        ProductLikeDataset.ProductDoc secondPass = dataset.product(777);
        assertEquals(firstPass, secondPass);
    }

    @Test
    public void fieldInvariantsHoldAcrossSample() {
        ProductLikeDataset dataset = new ProductLikeDataset(99L);
        Set<String> knownBrands = new HashSet<>(List.of(ProductLikeDataset.BRANDS));
        Set<String> knownCategories = new HashSet<>();
        for (ProductLikeDataset.CategoryNode node : ProductLikeDataset.CATEGORY_NODES) {
            knownCategories.add(node.fullPath());
        }
        for (long i = 0; i < SAMPLE_SIZE; i++) {
            ProductLikeDataset.ProductDoc doc = dataset.product(i);
            assertTrue(knownBrands.contains(doc.brand()), "unknown brand: " + doc.brand());
            assertTrue(knownCategories.contains(doc.category()), "unknown category: " + doc.category());
            assertEquals(3, doc.categoryPath().size());
            assertTrue(doc.category().startsWith(doc.categoryPath().get(0)), "category should nest under top ancestor");
            assertTrue(doc.price() > 0, "price should be positive: " + doc.price());
            assertTrue(doc.discountPercent() >= 0 && doc.discountPercent() <= 70, "discount out of range: " + doc.discountPercent());
            assertTrue(doc.rating() >= 1.0 && doc.rating() <= 5.0, "rating out of range: " + doc.rating());
            assertTrue(doc.reviewCount() >= 0, "review count should be non-negative");
            assertTrue(doc.stockQty() >= 0, "stock qty should be non-negative");
            if (!doc.inStock()) {
                assertEquals(0, doc.stockQty());
            }
            assertNotNull(doc.color());
            assertTrue(doc.updatedAtMillis() >= doc.createdAtMillis(), "updated_at should not precede created_at");
            int wordCount = doc.description().isEmpty() ? 0 : doc.description().split("\\s+").length;
            assertTrue(wordCount >= 30 && wordCount <= 200, "description word count out of range: " + wordCount);
            assertTrue(doc.tags().size() <= 6, "unexpected tag count: " + doc.tags().size());
            assertTrue(!doc.name().isBlank(), "name should not be blank");
            assertTrue(!doc.sku().isBlank(), "sku should not be blank");
        }
    }

    @Test
    public void toSourceProducesExpectedFieldsAndOptionalSparseFields() {
        ProductLikeDataset dataset = new ProductLikeDataset(3L);
        ProductLikeDataset.ProductDoc doc = dataset.product(0);
        Map<String, Object> source = dataset.toSource(doc);
        assertEquals(doc.sku(), source.get("sku"));
        assertEquals(doc.name(), source.get("name"));
        assertEquals(doc.brand(), source.get("brand"));
        assertEquals(doc.category(), source.get("category"));
        assertEquals(doc.categoryPath(), source.get("category_path"));
        assertEquals(doc.inStock(), source.get("in_stock"));
        assertTrue(source.get("created_at") instanceof String, "created_at should be an ISO string");
        assertTrue(source.get("suggest") instanceof Map<?, ?>, "suggest should be a completion input map");
        if (doc.discountPercent() <= 0) {
            assertTrue(!source.containsKey("discount_percent"), "discount_percent should be omitted when zero");
        } else {
            assertEquals(doc.discountPercent(), source.get("discount_percent"));
        }
        if (doc.size() == null) {
            assertTrue(!source.containsKey("size"), "size should be omitted when not applicable");
        } else {
            assertEquals(doc.size(), source.get("size"));
        }
    }

    @Test
    public void mappingDeclaresExpectedFieldTypes() {
        var mapping = ProductLikeDataset.mapping(3);
        @SuppressWarnings("unchecked")
        var mappings = (Map<String, Object>) mapping.get("mappings");
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) mappings.get("properties");
        assertEquals("keyword", ((Map<?, ?>) properties.get("brand")).get("type"));
        assertEquals("keyword", ((Map<?, ?>) properties.get("category")).get("type"));
        assertEquals("scaled_float", ((Map<?, ?>) properties.get("price")).get("type"));
        assertEquals("boolean", ((Map<?, ?>) properties.get("in_stock")).get("type"));
        assertEquals("completion", ((Map<?, ?>) properties.get("suggest")).get("type"));
        Map<?, ?> nameField = (Map<?, ?>) properties.get("name");
        assertEquals("text", nameField.get("type"));
        assertEquals("english", nameField.get("analyzer"));
        Map<?, ?> nameSubFields = (Map<?, ?>) nameField.get("fields");
        assertEquals("keyword", ((Map<?, ?>) nameSubFields.get("keyword")).get("type"));
        @SuppressWarnings("unchecked")
        var settings = (Map<String, Object>) mapping.get("settings");
        assertEquals(3, settings.get("number_of_shards"));
    }

    @Test
    public void categoryTreeHasTwoHundredLeavesAndFiveHundredBrands() {
        assertEquals(200, ProductLikeDataset.CATEGORY_NODES.size());
        assertEquals(500, ProductLikeDataset.BRANDS.length);
        Set<String> uniqueBrands = new HashSet<>(List.of(ProductLikeDataset.BRANDS));
        assertEquals(500, uniqueBrands.size());
        Set<String> uniquePaths = new HashSet<>();
        for (ProductLikeDataset.CategoryNode node : ProductLikeDataset.CATEGORY_NODES) {
            uniquePaths.add(node.fullPath());
        }
        assertEquals(200, uniquePaths.size());
    }
}
