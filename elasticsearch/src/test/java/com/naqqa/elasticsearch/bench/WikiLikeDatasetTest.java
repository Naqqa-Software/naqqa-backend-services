package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.bench.dataset.WikiLikeDataset;
import com.naqqa.elasticsearch.test.Test;

import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class WikiLikeDatasetTest {

    @Test
    public void sameSeedProducesIdenticalDocs() {
        WikiLikeDataset a = new WikiLikeDataset(42L);
        WikiLikeDataset b = new WikiLikeDataset(42L);
        for (long i = 0; i < 50; i++) {
            assertEquals(a.doc(i), b.doc(i));
        }
    }

    @Test
    public void differentSeedProducesDifferentDocs() {
        WikiLikeDataset a = new WikiLikeDataset(1L);
        WikiLikeDataset b = new WikiLikeDataset(2L);
        boolean anyDifferent = false;
        for (long i = 0; i < 20; i++) {
            if (!a.doc(i).body().equals(b.doc(i).body())) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "expected different seeds to diverge");
    }

    @Test
    public void isOrderIndependentAndRepeatable() {
        WikiLikeDataset dataset = new WikiLikeDataset(7L);
        WikiLikeDataset.WikiDoc firstPass = dataset.doc(123);
        for (long i = 0; i < 300; i++) {
            dataset.doc(i);
        }
        WikiLikeDataset.WikiDoc secondPass = dataset.doc(123);
        assertEquals(firstPass, secondPass);
    }

    @Test
    public void bodyLengthAndCategoriesAreRealistic() {
        WikiLikeDataset dataset = new WikiLikeDataset(99L);
        Set<String> knownCategories = Set.of(dataset.categories());
        for (long i = 0; i < 200; i++) {
            WikiLikeDataset.WikiDoc doc = dataset.doc(i);
            int wordCount = doc.body().isEmpty() ? 0 : doc.body().split("\\s+").length;
            assertTrue(wordCount >= 50 && wordCount <= 2000, "word count out of range: " + wordCount);
            assertTrue(!doc.categories().isEmpty() && doc.categories().size() <= 3, "unexpected category count");
            for (String c : doc.categories()) {
                assertTrue(knownCategories.contains(c), "unknown category: " + c);
            }
            assertTrue(doc.popularity() >= 1, "popularity should be positive");
            assertTrue(!doc.title().isBlank(), "title should not be blank");
        }
    }

    @Test
    public void vocabularyMixesCommonAndSyntheticWords() {
        WikiLikeDataset dataset = new WikiLikeDataset(5L);
        boolean sawCommon = false;
        boolean sawSynthetic = false;
        for (long i = 0; i < 500; i++) {
            String[] words = dataset.doc(i).body().split("\\s+");
            for (String w : words) {
                if (w.startsWith("syn")) {
                    sawSynthetic = true;
                } else {
                    sawCommon = true;
                }
            }
        }
        assertTrue(sawCommon, "expected at least some common words");
        assertTrue(sawSynthetic, "expected at least some synthetic long-tail words");
    }

    @Test
    public void toSourceProducesExpectedFields() {
        WikiLikeDataset dataset = new WikiLikeDataset(3L);
        WikiLikeDataset.WikiDoc doc = dataset.doc(0);
        var source = dataset.toSource(doc);
        assertEquals(doc.title(), source.get("title"));
        assertEquals(doc.body(), source.get("body"));
        assertEquals(doc.categories(), source.get("categories"));
        assertEquals(doc.popularity(), ((Number) source.get("popularity")).longValue());
        assertTrue(source.get("timestamp") instanceof String, "timestamp should be an ISO string");
    }

    @Test
    public void mappingDeclaresExpectedFieldTypes() {
        var mapping = WikiLikeDataset.mapping(2);
        @SuppressWarnings("unchecked")
        var mappings = (java.util.Map<String, Object>) mapping.get("mappings");
        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>) mappings.get("properties");
        assertEquals("keyword", ((java.util.Map<?, ?>) properties.get("categories")).get("type"));
        assertEquals("text", ((java.util.Map<?, ?>) properties.get("body")).get("type"));
        assertEquals("date", ((java.util.Map<?, ?>) properties.get("timestamp")).get("type"));
        assertEquals("long", ((java.util.Map<?, ?>) properties.get("popularity")).get("type"));
    }
}
