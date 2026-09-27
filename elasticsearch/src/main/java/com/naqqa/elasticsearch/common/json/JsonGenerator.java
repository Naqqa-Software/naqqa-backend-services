package com.naqqa.elasticsearch.common.json;

import com.naqqa.elasticsearch.common.xcontent.XContentGenerator;
import com.naqqa.elasticsearch.common.xcontent.XContentType;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;

public final class JsonGenerator implements XContentGenerator {

    private enum Ctx { OBJECT, ARRAY }

    private final Writer out;
    private final boolean pretty;
    private final Deque<Ctx> stack = new ArrayDeque<>();
    private final Deque<Boolean> firstInScope = new ArrayDeque<>();
    private boolean pendingFieldName;
    private boolean closed;

    public JsonGenerator(Writer out, boolean pretty) {
        this.out = out;
        this.pretty = pretty;
    }

    public JsonGenerator(OutputStream out, boolean pretty) {
        this(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8), pretty);
    }

    @Override
    public XContentType contentType() {
        return XContentType.JSON;
    }

    @Override
    public boolean isPrettyPrint() {
        return pretty;
    }

    private void write(String s) {
        try {
            out.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(char c) {
        try {
            out.write(c);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void beforeValue() {
        if (stack.isEmpty()) {
            return;
        }
        Ctx ctx = stack.peek();
        if (ctx == Ctx.ARRAY) {
            boolean first = firstInScope.peek();
            if (!first) {
                write(',');
            } else {
                firstInScope.pop();
                firstInScope.push(false);
            }
            newlineIndent();
        } else if (ctx == Ctx.OBJECT && !pendingFieldName) {
            throw new IllegalStateException("Expected field name before value in object");
        }
        pendingFieldName = false;
    }

    private void newlineIndent() {
        if (!pretty) {
            return;
        }
        write('\n');
        for (int i = 0; i < stack.size(); i++) {
            write("  ");
        }
    }

    @Override
    public XContentGenerator writeStartObject() {
        beforeValue();
        write('{');
        stack.push(Ctx.OBJECT);
        firstInScope.push(true);
        return this;
    }

    @Override
    public XContentGenerator writeEndObject() {
        stack.pop();
        boolean first = firstInScope.pop();
        if (!first) {
            newlineIndentClosing();
        }
        write('}');
        return this;
    }

    private void newlineIndentClosing() {
        if (!pretty) {
            return;
        }
        write('\n');
        for (int i = 0; i < stack.size(); i++) {
            write("  ");
        }
    }

    @Override
    public XContentGenerator writeStartArray() {
        beforeValue();
        write('[');
        stack.push(Ctx.ARRAY);
        firstInScope.push(true);
        return this;
    }

    @Override
    public XContentGenerator writeEndArray() {
        stack.pop();
        boolean first = firstInScope.pop();
        if (!first) {
            newlineIndentClosing();
        }
        write(']');
        return this;
    }

    @Override
    public XContentGenerator writeFieldName(String name) {
        if (stack.isEmpty() || stack.peek() != Ctx.OBJECT) {
            throw new IllegalStateException("Not in an object context");
        }
        boolean first = firstInScope.pop();
        if (!first) {
            write(',');
        }
        firstInScope.push(false);
        newlineIndent();
        writeQuotedString(name);
        write(':');
        if (pretty) {
            write(' ');
        }
        pendingFieldName = true;
        return this;
    }

    private void writeQuotedString(String s) {
        write('"');
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> write("\\\"");
                case '\\' -> write("\\\\");
                case '\b' -> write("\\b");
                case '\f' -> write("\\f");
                case '\n' -> write("\\n");
                case '\r' -> write("\\r");
                case '\t' -> write("\\t");
                default -> {
                    if (c < 0x20) {
                        write(String.format("\\u%04x", (int) c));
                    } else {
                        write(c);
                    }
                }
            }
        }
        write('"');
    }

    @Override
    public XContentGenerator writeString(String value) {
        beforeValue();
        writeQuotedString(value);
        return this;
    }

    @Override
    public XContentGenerator writeNumber(int value) {
        beforeValue();
        write(Integer.toString(value));
        return this;
    }

    @Override
    public XContentGenerator writeNumber(long value) {
        beforeValue();
        write(Long.toString(value));
        return this;
    }

    @Override
    public XContentGenerator writeNumber(float value) {
        beforeValue();
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            writeQuotedString(Float.toString(value));
        } else {
            write(Float.toString(value));
        }
        return this;
    }

    @Override
    public XContentGenerator writeNumber(double value) {
        beforeValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            writeQuotedString(Double.toString(value));
        } else {
            write(Double.toString(value));
        }
        return this;
    }

    @Override
    public XContentGenerator writeNumber(BigInteger value) {
        beforeValue();
        write(value.toString());
        return this;
    }

    @Override
    public XContentGenerator writeNumber(BigDecimal value) {
        beforeValue();
        write(value.toString());
        return this;
    }

    @Override
    public XContentGenerator writeBoolean(boolean value) {
        beforeValue();
        write(value ? "true" : "false");
        return this;
    }

    @Override
    public XContentGenerator writeNull() {
        beforeValue();
        write("null");
        return this;
    }

    @Override
    public XContentGenerator writeBinary(byte[] value, int offset, int length) {
        beforeValue();
        writeQuotedString(Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(value, offset, offset + length)));
        return this;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.flush();
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
