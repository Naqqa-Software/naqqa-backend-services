package com.naqqa.elasticsearch.common.logging;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Supplier;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

final class ConsoleStreamHandler extends StreamHandler {

    ConsoleStreamHandler(Supplier<? extends OutputStream> target, Formatter formatter) {
        super(new CurrentStream(target), formatter);
    }

    @Override
    public synchronized void publish(LogRecord record) {
        super.publish(record);
        flush();
    }

    @Override
    public synchronized void close() {
        flush();
    }

    private static final class CurrentStream extends OutputStream {
        private final Supplier<? extends OutputStream> target;

        CurrentStream(Supplier<? extends OutputStream> target) {
            this.target = target;
        }

        @Override
        public void write(int b) throws IOException {
            target.get().write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            target.get().write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            target.get().flush();
        }
    }
}
