package com.naqqa.elasticsearch.script.painless;

import com.naqqa.elasticsearch.script.DocLookup;
import com.naqqa.elasticsearch.script.ScriptDocValues;
import com.naqqa.elasticsearch.script.ScriptSettings;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class PainlessBytecodeEquivalenceTest {

    public PainlessBytecodeEquivalenceTest() {
    }

    private Object viaInterpreter(String src, Map<String, Object> vars) {
        Ast.Source ast = Parser.parse(src);
        return new Interpreter(ast, null, ScriptSettings.defaults()).execute(vars, null);
    }

    private Object viaBytecode(String src, Map<String, Object> vars) {
        Ast.Source ast = Parser.parse(src);
        assertTrue(BytecodeCompiler.canCompile(ast), "expected compilable: " + src);
        return BytecodeCompiler.compile(ast, ScriptSettings.DEFAULT_MAX_LOOP_COUNTER).executable().exec(vars);
    }

    @Test
    public void arithmeticExpressionsMatch() {
        String[] scripts = {
            "1 + 2 * 3 - 4 / 2",
            "def x = 5; def y = 3; return (x + y) * (x - y);",
            "def a = 10; def b = 0; for (def i = 0; i < a; i++) { b += i; } return b;",
            "def f = true; def r = 0; if (f && (1 < 2)) { r = 1; } else { r = 2; } return r;",
            "def x = 2.5; return x * x + 1;",
            "def n = 6; def i = 0; def r = 1; while (i < n) { r *= (i + 1); i++; } return r;"
        };
        for (String s : scripts) {
            Object interpreted = viaInterpreter(s, new LinkedHashMap<>());
            Object compiled = viaBytecode(s, new LinkedHashMap<>());
            assertEquals(interpreted, compiled, s);
        }
    }

    @Test
    public void randomizedArithmeticMatches() {
        Random random = new Random(42);
        for (int t = 0; t < 30; t++) {
            int a = random.nextInt(200) - 100;
            int b = random.nextInt(50) + 1;
            String op = new String[]{"+", "-", "*", "/", "%"}[random.nextInt(5)];
            String src = "def a = " + a + "; def b = " + b + "; return a " + op + " b;";
            Object interpreted = viaInterpreter(src, new LinkedHashMap<>());
            Object compiled = viaBytecode(src, new LinkedHashMap<>());
            assertEquals(interpreted, compiled, src);
        }
    }

    @Test
    public void docAccessMatchesBetweenInterpreterAndBytecode() {
        Map<String, ScriptDocValues<?>> fields = new LinkedHashMap<>();
        fields.put("price", ScriptDocValues.doubles(12.5));
        DocLookup doc = DocLookup.of(fields);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("doc", doc);
        vars.put("params", Map.of("factor", 3.0));
        String src = "return doc['price'].value * params.factor;";
        assertEquals(viaInterpreter(src, vars), viaBytecode(src, vars));
    }

    @Test
    public void incDecMatchesBetweenInterpreterAndBytecode() {
        String src = "def x = 5; def a = x++; def b = ++x; return a * 100 + b;";
        assertEquals(viaInterpreter(src, new LinkedHashMap<>()), viaBytecode(src, new LinkedHashMap<>()));
    }
}
