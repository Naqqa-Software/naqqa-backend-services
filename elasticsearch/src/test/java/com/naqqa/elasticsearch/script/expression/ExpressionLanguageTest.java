package com.naqqa.elasticsearch.script.expression;

import com.naqqa.elasticsearch.script.CompiledScript;
import com.naqqa.elasticsearch.script.DocLookup;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptDocValues;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;

public final class ExpressionLanguageTest {

    public ExpressionLanguageTest() {
    }

    private Object run(String source, Map<String, Object> vars) {
        ExpressionScriptEngine engine = new ExpressionScriptEngine();
        CompiledScript compiled = engine.compile("test", source, ScriptContext.SORT, Map.of());
        return compiled.execute(vars, null);
    }

    @Test
    public void arithmeticAndMathFunctions() {
        assertEquals(7.0, run("1 + 2 * 3", Map.of()));
        assertEquals(4.0, run("sqrt(16)", Map.of()));
        assertEquals(2.0, run("pow(2, 0) + min(1, 5) - 1 + 1", Map.of()));
        assertEquals(2.0, run("1 < 2 ? 2 : 3", Map.of()));
    }

    @Test
    public void paramsAndScoreAccess() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("params", Map.of("factor", 3.0));
        vars.put("_score", 2.0);
        assertEquals(6.0, run("_score * params.factor", vars));
    }

    @Test
    public void docValueAccessors() {
        Map<String, ScriptDocValues<?>> fields = new LinkedHashMap<>();
        fields.put("price", ScriptDocValues.doubles(10.0, 20.0, 30.0));
        DocLookup doc = DocLookup.of(fields);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("doc", doc);
        assertEquals(10.0, run("doc['price'].min()", vars));
        assertEquals(30.0, run("doc['price'].max()", vars));
        assertEquals(20.0, run("doc['price'].avg()", vars));
        assertEquals(60.0, run("doc['price'].sum()", vars));
        assertEquals(3.0, run("doc['price'].length", vars));
        assertEquals(10.0, run("doc['price'].value", vars));
    }

    @Test
    public void rejectsNonNumericSyntax() {
        ExpressionScriptEngine engine = new ExpressionScriptEngine();
        assertThrows(RuntimeException.class, () -> engine.compile("test", "'a string'", ScriptContext.SORT, Map.of()));
    }
}
