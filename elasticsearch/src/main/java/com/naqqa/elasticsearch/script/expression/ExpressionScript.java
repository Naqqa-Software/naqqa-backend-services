package com.naqqa.elasticsearch.script.expression;

import com.naqqa.elasticsearch.script.CompiledScript;
import com.naqqa.elasticsearch.script.DocLookup;
import com.naqqa.elasticsearch.script.Emitter;

import java.util.Map;

public final class ExpressionScript implements CompiledScript {

    private final ExpressionEvaluator evaluator;

    public ExpressionScript(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public Object execute(Map<String, Object> variables, Emitter emitter) {
        ExpressionContext ctx = new ExpressionContext() {
            @Override
            public double score() {
                Object s = variables.get("_score");
                return s instanceof Number n ? n.doubleValue() : 0.0;
            }

            @Override
            public Object param(String name) {
                Object params = variables.get("params");
                return params instanceof Map<?, ?> m ? m.get(name) : null;
            }

            @Override
            public DocLookup doc() {
                Object doc = variables.get("doc");
                if (doc instanceof DocLookup lookup) {
                    return lookup;
                }
                throw new IllegalStateException("no document access available in this context");
            }
        };
        return evaluator.evaluate(ctx);
    }
}
