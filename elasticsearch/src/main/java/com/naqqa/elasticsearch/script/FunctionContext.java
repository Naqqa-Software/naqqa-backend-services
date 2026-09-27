package com.naqqa.elasticsearch.script;

public interface FunctionContext {

    Object variable(String name);

    void emit(Object value);
}
