package com.naqqa.elasticsearch.script.painless;

import com.naqqa.elasticsearch.script.DocLookup;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptDocValues;
import com.naqqa.elasticsearch.script.ScriptSettings;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class PainlessLanguageTest {

    public PainlessLanguageTest() {
    }

    private Object run(String source, Map<String, Object> vars) {
        PainlessScriptEngine engine = new PainlessScriptEngine();
        return engine.compile("test", source, ScriptContext.FIELD, Map.of()).execute(vars);
    }

    @Test
    public void arithmeticAndPrecedence() {
        assertEquals(7, run("1 + 2 * 3", Map.of()));
        assertEquals(9, run("(1 + 2) * 3", Map.of()));
        assertEquals("a1", run("'a' + 1", Map.of()));
        assertEquals(true, run("1 < 2 ? true : false", Map.of()));
        assertEquals(5, run("def x = null; x ?: 5", Map.of()));
    }

    @Test
    public void controlFlow() {
        assertEquals(55, run("int total = 0; for (int i = 1; i <= 10; i++) { total += i; } return total;", Map.of()));
        assertEquals(10, run("int i = 0; int total = 0; while (i < 5) { total += 2; i++; } return total;", Map.of()));
        assertEquals(6L, run("long total = 0; for (def x : [1,2,3]) { total += x; } return total;", Map.of()));
        assertEquals(4, run("int i = 0; int c = 0; while (true) { i++; if (i == 2) continue; if (i > 5) break; c++; } return c;", Map.of()));
    }

    @Test
    public void userFunctionsAndLambdas() {
        String src = "int fib(int n) { if (n < 2) return n; return fib(n - 1) + fib(n - 2); } return fib(10);";
        assertEquals(55, run(src, Map.of()));
        assertEquals(List.of(1, 2, 3), run("def list = [3,1,2]; list.sort((a, b) -> a - b); return list;", Map.of()));
    }

    @Test
    public void collectionsLiteralsAndDocAccess() {
        Map<String, ScriptDocValues<?>> fields = new LinkedHashMap<>();
        fields.put("price", ScriptDocValues.doubles(10.0, 20.0));
        DocLookup doc = DocLookup.of(fields);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("doc", doc);
        vars.put("params", Map.of("factor", 2.0));
        assertEquals(20.0, run("doc['price'].value * params.factor", vars));
        assertEquals(2, run("doc['price'].size()", vars));
        assertEquals(1, run("def m = ['a': 1, 'b': 2]; return m.size() - 1;", Map.of()));
    }

    @Test
    public void tryCatchAndRegex() {
        assertEquals(-1, run("try { int x = 1 / 0; return 1; } catch (ArithmeticException e) { return -1; }", Map.of()));
        assertEquals(true, run("'abc123' =~ /[0-9]+/", Map.of()));
        assertEquals(true, run("'abc' ==~ /abc/", Map.of()));
        assertEquals(false, run("'abc' ==~ /abd/", Map.of()));
    }

    @Test
    public void numericLiteralsArraysAndMethodReferences() {
        assertEquals(255, run("0xFF", Map.of()));
        assertEquals(8, run("010", Map.of()));
        assertEquals(3L, run("3L", Map.of()));
        assertEquals(2.5f, run("2.5f", Map.of()));
        assertEquals(3, run("int[] a = new int[3]; a[0] = 1; a[1] = 2; a[2] = 3; return a.length;", Map.of()));
        assertEquals(6, run("int[] a = new int[]{1,2,3}; int s = 0; for (int i = 0; i < a.length; i++) { s += a[i]; } return s;", Map.of()));
        assertEquals(List.of(1, 4, 9), run("def list = [1,2,3]; def out = []; for (def x : list) { out.add(x * x); } return out;", Map.of()));
    }

    @Test
    public void scoreBuiltInsAndDebugExplain() {
        Map<String, ScriptDocValues<?>> fields = new LinkedHashMap<>();
        fields.put("v", ScriptDocValues.denseVector(new float[]{1f, 0f, 0f}));
        DocLookup doc = DocLookup.of(fields);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("doc", doc);
        vars.put("params", Map.of("q", List.of(1.0, 0.0, 0.0), "x", 42));
        PainlessScriptEngine engine = new PainlessScriptEngine();
        Object cosine = engine.compile("test", "cosineSimilarity(params.q, doc['v'])", ScriptContext.SCORE, Map.of()).execute(vars);
        assertEquals(1.0, cosine);
        double saturation = (double) engine.compile("test", "saturation(2.0, 2.0)", ScriptContext.SCORE, Map.of()).execute(vars);
        assertEquals(0.5, saturation, 1e-9);
        PainlessExplainException explain = assertThrows(PainlessExplainException.class,
            () -> engine.compile("test", "Debug.explain(params.x)", ScriptContext.SCORE, Map.of()).execute(vars));
        assertEquals(42, explain.value());
    }

    @Test
    public void sandboxRejectsDisallowedTypes() {
        PainlessScriptEngine engine = new PainlessScriptEngine();
        assertThrows(com.naqqa.elasticsearch.script.ScriptException.class,
            () -> engine.compile("test", "new Socket()", ScriptContext.FIELD, Map.of()));
    }

    @Test
    public void loopLimitIsEnforced() {
        PainlessScriptEngine engine = new PainlessScriptEngine(ScriptSettings.defaults().maxLoopCounter(100));
        com.naqqa.elasticsearch.script.CompiledScript compiled = engine.compile("test", "while (true) {}", ScriptContext.FIELD, Map.of());
        assertThrows(PainlessRuntimeError.class, () -> compiled.execute(Map.of()));
    }

    @Test
    public void updateContextMutatesSource() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("count", 1);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("_source", source);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("ctx", ctx);
        vars.put("params", Map.of());
        PainlessScriptEngine engine = new PainlessScriptEngine();
        engine.compile("test", "ctx._source.count = ctx._source.count + 1; ctx.op = 'update';", ScriptContext.UPDATE, Map.of()).execute(vars);
        assertEquals(2, source.get("count"));
        assertEquals("update", ctx.get("op"));
    }
}
