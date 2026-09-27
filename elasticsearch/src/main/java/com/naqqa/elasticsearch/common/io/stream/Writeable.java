package com.naqqa.elasticsearch.common.io.stream;

import java.io.IOException;
import java.io.UncheckedIOException;

public interface Writeable {

    @FunctionalInterface
    interface Reader<T> {
        T read(StreamInput in) throws IOException;
    }

    @FunctionalInterface
    interface Writer<T> {
        void write(StreamOutput out, T value) throws IOException;
    }

    void writeTo(StreamOutput out) throws IOException;

    default void writeToUnchecked(StreamOutput out) {
        try {
            writeTo(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
