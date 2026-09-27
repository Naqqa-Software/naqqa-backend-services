package com.naqqa.elasticsearch.script;

@FunctionalInterface
public interface Emitter {

    void emit(Object value);
}
