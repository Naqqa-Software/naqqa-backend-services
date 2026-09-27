package com.naqqa.elasticsearch.script.expression;

@FunctionalInterface
public interface ExpressionEvaluator {

    double evaluate(ExpressionContext context);
}
