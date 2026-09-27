package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnalyzeActionTest {

    @SuppressWarnings("unchecked")
    @Test
    public void analyzeWithBuiltinAnalyzer() {
        AnalyzeAction action = new AnalyzeAction();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("analyzer", "standard");
        request.put("text", "Quick Brown Fox");
        Map<String, Object> response = action.analyze(request);
        List<Map<String, Object>> tokens = (List<Map<String, Object>>) response.get("tokens");
        Assert.assertEquals(3, tokens.size());
        Assert.assertEquals("quick", tokens.get(0).get("token"));
        Assert.assertEquals(0, tokens.get(0).get("start_offset"));
        Assert.assertEquals("fox", tokens.get(2).get("token"));
        Assert.assertEquals(2, tokens.get(2).get("position"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void analyzeWithExplicitTokenizerAndFilters() {
        AnalyzeAction action = new AnalyzeAction();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tokenizer", "whitespace");
        request.put("filter", List.of("lowercase"));
        request.put("text", "Hello World");
        Map<String, Object> response = action.analyze(request);
        List<Map<String, Object>> tokens = (List<Map<String, Object>>) response.get("tokens");
        Assert.assertEquals(List.of("hello", "world"),
            tokens.stream().map(t -> t.get("token")).toList());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void customAnalyzerFromIndexSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        Map<String, Object> analyzer = new LinkedHashMap<>();
        Map<String, Object> myAnalyzer = new LinkedHashMap<>();
        myAnalyzer.put("type", "custom");
        myAnalyzer.put("tokenizer", "standard");
        myAnalyzer.put("filter", List.of("lowercase", "asciifolding"));
        analyzer.put("my_analyzer", myAnalyzer);
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("analyzer", analyzer);
        settings.put("analysis", analysis);

        AnalysisRegistry registry = new AnalysisRegistry();
        IndexAnalyzers indexAnalyzers = registry.build(settings);
        List<String> terms = indexAnalyzers.get("my_analyzer").analyze(null, "Café MULLER");
        Assert.assertEquals(List.of("cafe", "muller"), terms);
        indexAnalyzers.close();
    }

    @Test
    public void unknownAnalyzerThrowsElasticsearchStyleError() {
        AnalyzeAction action = new AnalyzeAction();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("analyzer", "does_not_exist");
        request.put("text", "hello");
        Assert.assertThrows(IllegalArgumentException.class, () -> action.analyze(request));
    }
}
