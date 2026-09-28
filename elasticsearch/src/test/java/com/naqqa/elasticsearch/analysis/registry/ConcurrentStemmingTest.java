package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class ConcurrentStemmingTest {

    private static final String TEXT = "The international organizations were generously supporting "
        + "universities and running wonderful experiments with stainless steel luggage expandable spinners";

    private static void assertThreadSafe(String label, Analyzer analyzer, String text) throws Exception {
        List<String> expected = analyzer.analyze(null, text);
        int threads = 8;
        int iterations = 400;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread w = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations && failure.get() == null; i++) {
                        List<String> got = analyzer.analyze(null, text);
                        if (!expected.equals(got)) {
                            failure.compareAndSet(null, new AssertionError(label + " expected " + expected + " but got " + got));
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            workers.add(w);
            w.start();
        }
        start.countDown();
        for (Thread w : workers) {
            w.join();
        }
        if (failure.get() != null) {
            throw new AssertionError(label + " failed under concurrency: " + failure.get(), failure.get());
        }
    }

    @Test
    public void builtinLanguageAnalyzersAreThreadSafe() throws Exception {
        assertThreadSafe("english", BuiltinAnalyzers.get("english"), TEXT);
        assertThreadSafe("french", BuiltinAnalyzers.get("french"), "Les organisations internationales soutenaient généreusement les universités");
        assertThreadSafe("russian", BuiltinAnalyzers.get("russian"), "Международные организации щедро поддерживали университеты и эксперименты");
        assertThreadSafe("romanian", BuiltinAnalyzers.get("romanian"), "Organizațiile internaționale sprijineau generos universitățile și experimentele");
        assertThreadSafe("turkish", BuiltinAnalyzers.get("turkish"), "Uluslararası kuruluşlar üniversiteleri cömertçe destekliyorlardı");
    }

    @Test
    public void stemmerAndSnowballFiltersAreThreadSafe() throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>();
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("en_stem", Map.of("type", "stemmer", "language", "english"));
        filter.put("sb_stem", Map.of("type", "snowball", "language", "English"));
        Map<String, Object> analyzer = new LinkedHashMap<>();
        analyzer.put("a_stemmer", Map.of("type", "custom", "tokenizer", "standard", "filter", List.of("lowercase", "en_stem")));
        analyzer.put("a_snowball", Map.of("type", "custom", "tokenizer", "standard", "filter", List.of("lowercase", "sb_stem")));
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("filter", filter);
        analysis.put("analyzer", analyzer);
        settings.put("analysis", analysis);
        IndexAnalyzers indexAnalyzers = new AnalysisRegistry().build(settings);
        assertThreadSafe("stemmer filter", indexAnalyzers.get("a_stemmer"), TEXT);
        assertThreadSafe("snowball filter", indexAnalyzers.get("a_snowball"), TEXT);
        Assert.assertTrue(indexAnalyzers.get("a_stemmer").analyze(null, "universities").contains("universiti"));
        indexAnalyzers.close();
    }
}
