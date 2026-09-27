package com.naqqa.elasticsearch.script;

import com.naqqa.elasticsearch.script.functions.ScoreScriptFunctions;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ScriptContext {

    public enum ReturnType {
        VOID, OBJECT, DOUBLE, BOOLEAN, LONG, STRING
    }

    public record Variable(String name, Class<?> type, boolean readOnly) {
    }

    private static final Map<String, ScriptContext> REGISTRY = new ConcurrentHashMap<>();

    public static final int MAX_EMITTED_VALUES = 100;

    public static final ScriptContext SCORE = builder("score").var("params", Map.class).var("doc", Object.class).var("_score", double.class)
        .returns(ReturnType.DOUBLE).functions(ScoreScriptFunctions.all()).register();
    public static final ScriptContext FILTER = builder("filter").var("params", Map.class).var("doc", Object.class)
        .returns(ReturnType.BOOLEAN).register();
    public static final ScriptContext FIELD = builder("field").var("params", Map.class).var("doc", Object.class).var("_source", Map.class)
        .returns(ReturnType.OBJECT).register();
    public static final ScriptContext UPDATE = builder("update").var("params", Map.class).var("ctx", Map.class)
        .returns(ReturnType.VOID).register();
    public static final ScriptContext INGEST = builder("ingest").var("params", Map.class).var("ctx", Map.class)
        .returns(ReturnType.VOID).register();
    public static final ScriptContext PROCESSOR_CONDITIONAL = builder("processor_conditional").var("params", Map.class).var("ctx", Map.class)
        .returns(ReturnType.BOOLEAN).register();
    public static final ScriptContext INGEST_TEMPLATE = builder("ingest_template").var("params", Map.class)
        .returns(ReturnType.STRING).register();
    public static final ScriptContext AGGS = builder("aggs").var("params", Map.class).var("doc", Object.class).var("_score", double.class)
        .var("_value", Object.class).returns(ReturnType.OBJECT).register();
    public static final ScriptContext AGGS_INIT = builder("aggs_init").var("params", Map.class).var("state", Map.class)
        .returns(ReturnType.VOID).register();
    public static final ScriptContext AGGS_MAP = builder("aggs_map").var("params", Map.class).var("state", Map.class).var("doc", Object.class)
        .var("_score", double.class).returns(ReturnType.VOID).register();
    public static final ScriptContext AGGS_COMBINE = builder("aggs_combine").var("params", Map.class).var("state", Map.class)
        .returns(ReturnType.OBJECT).register();
    public static final ScriptContext AGGS_REDUCE = builder("aggs_reduce").var("params", Map.class).var("states", List.class)
        .returns(ReturnType.OBJECT).register();
    public static final ScriptContext SORT = builder("sort").var("params", Map.class).var("doc", Object.class).var("_score", double.class)
        .returns(ReturnType.OBJECT).register();
    public static final ScriptContext NUMBER_SORT = builder("number_sort").var("params", Map.class).var("doc", Object.class).var("_score", double.class)
        .returns(ReturnType.DOUBLE).register();
    public static final ScriptContext STRING_SORT = builder("string_sort").var("params", Map.class).var("doc", Object.class).var("_score", double.class)
        .returns(ReturnType.STRING).register();
    public static final ScriptContext TERMS_SET = builder("terms_set").var("params", Map.class).var("doc", Object.class)
        .returns(ReturnType.DOUBLE).register();
    public static final ScriptContext LONG_FIELD = runtime("long_field", "long");
    public static final ScriptContext DOUBLE_FIELD = runtime("double_field", "double");
    public static final ScriptContext KEYWORD_FIELD = runtime("keyword_field", "keyword");
    public static final ScriptContext BOOLEAN_FIELD = runtime("boolean_field", "boolean");
    public static final ScriptContext DATE_FIELD = runtime("date_field", "date");
    public static final ScriptContext GEO_POINT_FIELD = runtime("geo_point_field", "geo_point");
    public static final ScriptContext IP_FIELD = runtime("ip_field", "ip");
    public static final ScriptContext COMPOSITE_FIELD = runtime("composite_field", "composite");
    public static final ScriptContext SIMILARITY = builder("similarity").var("params", Map.class).var("weight", double.class)
        .var("query", Map.class).var("field", Map.class).var("term", Map.class).var("doc", Map.class)
        .returns(ReturnType.DOUBLE).register();
    public static final ScriptContext SIMILARITY_WEIGHT = builder("similarity_weight").var("params", Map.class)
        .var("query", Map.class).var("field", Map.class).var("term", Map.class)
        .returns(ReturnType.DOUBLE).register();
    public static final ScriptContext BUCKET_SCRIPT = builder("bucket_aggregation").var("params", Map.class)
        .returns(ReturnType.OBJECT).register();
    public static final ScriptContext BUCKET_SELECTOR = builder("bucket_aggregation_selector").var("params", Map.class)
        .returns(ReturnType.BOOLEAN).register();
    public static final ScriptContext TEMPLATE = builder("template").var("params", Map.class)
        .returns(ReturnType.STRING).register();
    public static final ScriptContext REINDEX = builder("reindex").var("params", Map.class).var("ctx", Map.class)
        .returns(ReturnType.VOID).register();
    public static final ScriptContext UPDATE_BY_QUERY = builder("update_by_query").var("params", Map.class).var("ctx", Map.class)
        .returns(ReturnType.VOID).register();
    public static final ScriptContext WATCHER_CONDITION = builder("watcher_condition").var("params", Map.class).var("ctx", Map.class)
        .returns(ReturnType.BOOLEAN).register();

    private final String name;
    private final List<Variable> variables;
    private final ReturnType returnType;
    private final Map<String, ScriptFunction> functions;
    private final String runtimeFieldType;

    private ScriptContext(String name, List<Variable> variables, ReturnType returnType, Map<String, ScriptFunction> functions, String runtimeFieldType) {
        this.name = name;
        this.variables = List.copyOf(variables);
        this.returnType = returnType;
        this.functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));
        this.runtimeFieldType = runtimeFieldType;
    }

    public String name() {
        return name;
    }

    public List<Variable> variables() {
        return variables;
    }

    public Variable variable(String varName) {
        for (Variable v : variables) {
            if (v.name().equals(varName)) {
                return v;
            }
        }
        return null;
    }

    public ReturnType returnType() {
        return returnType;
    }

    public Map<String, ScriptFunction> functions() {
        return functions;
    }

    public ScriptFunction function(String functionName, int arity) {
        return functions.get(functionName + "/" + arity);
    }

    public String runtimeFieldType() {
        return runtimeFieldType;
    }

    public boolean isRuntimeField() {
        return runtimeFieldType != null;
    }

    public static ScriptContext byName(String name) {
        ScriptContext c = REGISTRY.get(name);
        if (c == null) {
            throw new IllegalArgumentException("script context [" + name + "] not supported");
        }
        return c;
    }

    public static ScriptContext runtimeFieldContext(String fieldType) {
        return byName(fieldType + "_field");
    }

    public static Collection<ScriptContext> all() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScriptContext c && c.name.equals(name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    private static ScriptContext runtime(String name, String type) {
        Builder b = builder(name).var("params", Map.class).var("doc", Object.class).returns(ReturnType.VOID);
        b.runtimeFieldType = type;
        switch (type) {
            case "long" -> b.function("emit", 1, (c, a) -> emitValue(c, toLong(a[0])));
            case "double" -> b.function("emit", 1, (c, a) -> emitValue(c, toDouble(a[0])));
            case "keyword", "ip" -> b.function("emit", 1, (c, a) -> emitValue(c, toStringValue(a[0])));
            case "boolean" -> b.function("emit", 1, (c, a) -> emitValue(c, toBoolean(a[0])));
            case "date" -> b.function("emit", 1, (c, a) -> emitValue(c, toMillis(a[0])));
            case "geo_point" -> {
                b.function("emit", 2, (c, a) -> emitValue(c, new GeoPoint(toDouble(a[0]), toDouble(a[1]))));
                b.function("emit", 1, (c, a) -> emitValue(c, GeoPoint.parse(a[0])));
            }
            case "composite" -> {
                b.function("emit", 2, (c, a) -> emitValue(c, Map.entry(toStringValue(a[0]), a[1])));
                b.function("emit", 1, (c, a) -> {
                    if (!(a[0] instanceof Map<?, ?> m)) {
                        throw new ClassCastException("composite emit requires a map but got [" + (a[0] == null ? "null" : a[0].getClass().getName()) + "]");
                    }
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        emitValue(c, Map.entry(String.valueOf(e.getKey()), e.getValue()));
                    }
                    return null;
                });
            }
            default -> throw new IllegalArgumentException("unknown runtime type " + type);
        }
        return b.register();
    }

    private static Object emitValue(FunctionContext c, Object v) {
        c.emit(v);
        return null;
    }

    private static Long toLong(Object o) {
        if (o instanceof Long || o instanceof Integer || o instanceof Short || o instanceof Byte) {
            return ((Number) o).longValue();
        }
        if (o instanceof Character c) {
            return (long) c;
        }
        throw new ClassCastException("Cannot cast [" + typeOf(o) + "] to [long]");
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        throw new ClassCastException("Cannot cast [" + typeOf(o) + "] to [double]");
    }

    private static String toStringValue(Object o) {
        if (o instanceof CharSequence cs) {
            return cs.toString();
        }
        throw new ClassCastException("Cannot cast [" + typeOf(o) + "] to [java.lang.String]");
    }

    private static Boolean toBoolean(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        throw new ClassCastException("Cannot cast [" + typeOf(o) + "] to [boolean]");
    }

    private static Long toMillis(Object o) {
        if (o instanceof ZonedDateTime z) {
            return z.toInstant().toEpochMilli();
        }
        if (o instanceof java.time.Instant i) {
            return i.toEpochMilli();
        }
        return toLong(o);
    }

    private static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    public static final class Builder {
        private final String name;
        private final List<Variable> variables = new ArrayList<>();
        private ReturnType returnType = ReturnType.OBJECT;
        private final Map<String, ScriptFunction> functions = new LinkedHashMap<>();
        private String runtimeFieldType;

        private Builder(String name) {
            this.name = name;
        }

        public Builder var(String varName, Class<?> type) {
            variables.add(new Variable(varName, type, true));
            return this;
        }

        public Builder mutableVar(String varName, Class<?> type) {
            variables.add(new Variable(varName, type, false));
            return this;
        }

        public Builder returns(ReturnType type) {
            this.returnType = type;
            return this;
        }

        public Builder function(String functionName, int arity, ScriptFunction function) {
            functions.put(functionName + "/" + arity, function);
            return this;
        }

        public Builder functions(Map<String, ScriptFunction> fns) {
            functions.putAll(fns);
            return this;
        }

        public Builder runtimeFieldType(String type) {
            this.runtimeFieldType = type;
            return this;
        }

        public ScriptContext build() {
            return new ScriptContext(name, variables, returnType, functions, runtimeFieldType);
        }

        public ScriptContext register() {
            ScriptContext c = build();
            REGISTRY.put(name, c);
            return c;
        }
    }
}
