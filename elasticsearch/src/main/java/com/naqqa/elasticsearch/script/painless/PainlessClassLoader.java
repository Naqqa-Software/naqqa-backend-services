package com.naqqa.elasticsearch.script.painless;

public final class PainlessClassLoader extends ClassLoader {

    public PainlessClassLoader(ClassLoader parent) {
        super(parent);
    }

    public Class<?> define(String name, byte[] bytes) {
        return defineClass(name, bytes, 0, bytes.length);
    }
}
