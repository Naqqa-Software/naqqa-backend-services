package com.naqqa.elasticsearch.script;

@FunctionalInterface
public interface ScriptFunction {

    Object call(FunctionContext context, Object[] args);
}
