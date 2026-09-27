package com.naqqa.elasticsearch.script.expression;

import com.naqqa.elasticsearch.script.DocLookup;

public interface ExpressionContext {

    double score();

    Object param(String name);

    DocLookup doc();
}
