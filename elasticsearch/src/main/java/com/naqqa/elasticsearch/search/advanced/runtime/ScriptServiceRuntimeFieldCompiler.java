package com.naqqa.elasticsearch.search.advanced.runtime;

import com.naqqa.elasticsearch.index.mapper.RuntimeFieldScript;
import com.naqqa.elasticsearch.index.mapper.ScriptCompiler;
import com.naqqa.elasticsearch.script.DocLookup;
import com.naqqa.elasticsearch.script.Script;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptDocValues;
import com.naqqa.elasticsearch.script.ScriptService;
import com.naqqa.elasticsearch.script.ScriptType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScriptServiceRuntimeFieldCompiler implements ScriptCompiler {

    private final ScriptService scriptService;

    public ScriptServiceRuntimeFieldCompiler(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    @Override
    public RuntimeFieldScript compile(String source, String lang, Map<String, Object> params, String targetFieldType) {
        ScriptContext context = ScriptContext.runtimeFieldContext(targetFieldType);
        Script script = new Script(ScriptType.INLINE, lang, source, Map.of(), params == null ? Map.of() : params);
        return sourceAsMap -> {
            List<Object> values = new ArrayList<>();
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("doc", toDocLookup(sourceAsMap));
            scriptService.execute(script, context, variables, values::add);
            return values;
        };
    }

    private static DocLookup toDocLookup(Map<String, Object> sourceAsMap) {
        Map<String, ScriptDocValues<?>> fields = new LinkedHashMap<>();
        if (sourceAsMap != null) {
            for (Map.Entry<String, Object> e : sourceAsMap.entrySet()) {
                fields.put(e.getKey(), toScriptDocValues(e.getValue()));
            }
        }
        return DocLookup.of(fields);
    }

    @SuppressWarnings("unchecked")
    private static ScriptDocValues<?> toScriptDocValues(Object raw) {
        List<Object> values;
        if (raw instanceof List<?> l) {
            values = (List<Object>) l;
        } else if (raw == null) {
            values = List.of();
        } else {
            values = List.of(raw);
        }
        if (values.isEmpty()) {
            return new ScriptDocValues.Strings(List.of());
        }
        Object first = values.get(0);
        if (first instanceof Long || first instanceof Integer || first instanceof Short || first instanceof Byte) {
            long[] arr = new long[values.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = ((Number) values.get(i)).longValue();
            }
            return ScriptDocValues.longs(arr);
        }
        if (first instanceof Double || first instanceof Float) {
            double[] arr = new double[values.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = ((Number) values.get(i)).doubleValue();
            }
            return ScriptDocValues.doubles(arr);
        }
        if (first instanceof Boolean) {
            boolean[] arr = new boolean[values.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = (Boolean) values.get(i);
            }
            return ScriptDocValues.booleans(arr);
        }
        String[] arr = new String[values.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = String.valueOf(values.get(i));
        }
        return ScriptDocValues.strings(arr);
    }
}
