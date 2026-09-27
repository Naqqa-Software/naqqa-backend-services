package com.naqqa.elasticsearch.script;

import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ScriptServiceTest {

    public ScriptServiceTest() {
    }

    @Test
    public void compilesAndExecutesInlineScoreScript() {
        ScriptService service = ScriptService.defaults();
        Script script = Script.inline("painless", "params.base + _score", Map.of("base", 10.0));
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("_score", 5.0);
        Object result = service.execute(script, ScriptContext.SCORE, vars);
        assertEquals(15.0, result);
    }

    @Test
    public void storedScriptCrudAndCompileById() {
        ScriptService service = ScriptService.defaults();
        service.putStoredScript("double-it", new StoredScriptSource("painless", "params.value * 2", Map.of()));
        assertTrue(service.hasStoredScript("double-it"));
        Script script = Script.stored("double-it", Map.of("value", 21));
        Object result = service.execute(script, ScriptContext.FIELD, Map.of());
        assertEquals(42, result);
        service.deleteStoredScript("double-it");
        assertThrows(IllegalArgumentException.class, () -> service.getStoredScript("double-it"));
    }

    @Test
    public void storedScriptFromMapBodyAndStringShorthand() {
        ScriptService service = ScriptService.defaults();
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> scriptField = new LinkedHashMap<>();
        scriptField.put("lang", "painless");
        scriptField.put("source", "params.a + params.b");
        body.put("script", scriptField);
        service.putStoredScript("adder", StoredScriptSource.parse(body));
        Object result = service.execute(Script.stored("adder", Map.of("a", 1, "b", 2)), ScriptContext.FIELD, Map.of());
        assertEquals(3, result);

        Script shorthand = Script.parse("1 + 1");
        assertEquals(2, service.execute(shorthand, ScriptContext.FIELD, Map.of()));
    }

    @Test
    public void compilationCacheReusesCompiledScript() {
        ScriptSettings settings = ScriptSettings.defaults();
        ScriptService service = new ScriptService(settings);
        Script script = Script.inline("painless", "1 + 1", Map.of());
        service.execute(script, ScriptContext.FIELD, Map.of());
        long afterFirst = service.compilationCount();
        service.execute(script, ScriptContext.FIELD, Map.of());
        assertEquals(afterFirst, service.compilationCount());
    }

    @Test
    public void compilationRateLimitTrips() {
        ScriptSettings settings = ScriptSettings.defaults().maxCompilationsRate("1/1h");
        ScriptService service = new ScriptService(settings);
        service.execute(Script.inline("painless", "1", Map.of()), ScriptContext.FIELD, Map.of());
        assertThrows(ScriptCircuitBreakingException.class,
            () -> service.execute(Script.inline("painless", "2", Map.of()), ScriptContext.FIELD, Map.of()));
    }

    @Test
    public void disallowedContextIsRejected() {
        ScriptSettings settings = ScriptSettings.defaults().allowedContexts("score");
        ScriptService service = new ScriptService(settings);
        assertThrows(IllegalArgumentException.class,
            () -> service.execute(Script.inline("painless", "1", Map.of()), ScriptContext.FIELD, Map.of()));
    }

    @Test
    public void scriptedMetricAggregationFlow() {
        ScriptService service = ScriptService.defaults();
        Map<String, Object> state = new LinkedHashMap<>();
        service.execute(Script.inline("painless", "state.total = 0", Map.of()), ScriptContext.AGGS_INIT, Map.of("state", state));
        for (int price : List.of(10, 20, 30)) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("state", state);
            Script mapScript = Script.inline("painless", "state.total = state.total + params.price", Map.of("price", price));
            service.execute(mapScript, ScriptContext.AGGS_MAP, vars);
        }
        Object shardResult = service.execute(Script.inline("painless", "state.total", Map.of()), ScriptContext.AGGS_COMBINE, Map.of("state", state));
        assertEquals(60, shardResult);
        Object reduced = service.execute(Script.inline("painless",
            "def total = 0; for (def s : states) { total += s; } return total;", Map.of()),
            ScriptContext.AGGS_REDUCE, Map.of("states", List.of(shardResult, 40)));
        assertEquals(100, reduced);
    }

    @Test
    public void runtimeFieldEmitCollectsValues() {
        ScriptService service = ScriptService.defaults();
        List<Object> emitted = new ArrayList<>();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("params", Map.of());
        service.execute(Script.inline("painless", "emit(21); emit(42);", Map.of()), ScriptContext.LONG_FIELD, vars, emitted::add);
        assertEquals(List.of(21L, 42L), emitted);
    }
}
