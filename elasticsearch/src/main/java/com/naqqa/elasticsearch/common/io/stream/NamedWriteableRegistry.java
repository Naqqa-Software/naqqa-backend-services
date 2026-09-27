package com.naqqa.elasticsearch.common.io.stream;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class NamedWriteableRegistry {

    public interface Entry {
    }

    private final Map<Class<?>, Map<String, Writeable.Reader<?>>> readers = new HashMap<>();

    public <T extends Writeable> void register(Class<T> categoryClass, String name, Writeable.Reader<? extends T> reader) {
        Map<String, Writeable.Reader<?>> byName = readers.computeIfAbsent(categoryClass, k -> new HashMap<>());
        if (byName.putIfAbsent(name, reader) != null) {
            throw new IllegalArgumentException(
                "NamedWriteable [" + categoryClass.getName() + "][" + name + "] is already registered"
            );
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends Writeable> T getReader(Class<T> categoryClass, String name, StreamInput in) throws IOException {
        Map<String, Writeable.Reader<?>> byName = readers.get(categoryClass);
        if (byName == null) {
            throw new IllegalArgumentException("Unknown NamedWriteable category [" + categoryClass.getName() + "]");
        }
        Writeable.Reader<?> reader = byName.get(name);
        if (reader == null) {
            throw new IllegalArgumentException(
                "Unknown NamedWriteable [" + categoryClass.getName() + "][" + name + "]"
            );
        }
        return (T) reader.read(in);
    }
}
