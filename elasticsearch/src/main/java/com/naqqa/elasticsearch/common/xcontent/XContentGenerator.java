package com.naqqa.elasticsearch.common.xcontent;

import java.io.Closeable;
import java.io.Flushable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public interface XContentGenerator extends Closeable, Flushable {

    DateTimeFormatter DEFAULT_DATE_PRINTER = new DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 3, 3, true)
        .appendOffset("+HH:MM", "Z")
        .toFormatter(java.util.Locale.ROOT)
        .withZone(ZoneOffset.UTC);

    XContentType contentType();

    boolean isPrettyPrint();

    XContentGenerator writeStartObject();

    default XContentGenerator writeStartObject(int size) {
        return writeStartObject();
    }

    XContentGenerator writeEndObject();

    XContentGenerator writeStartArray();

    default XContentGenerator writeStartArray(int size) {
        return writeStartArray();
    }

    XContentGenerator writeEndArray();

    XContentGenerator writeFieldName(String name);

    XContentGenerator writeString(String value);

    XContentGenerator writeNumber(int value);

    XContentGenerator writeNumber(long value);

    XContentGenerator writeNumber(float value);

    XContentGenerator writeNumber(double value);

    XContentGenerator writeNumber(BigInteger value);

    XContentGenerator writeNumber(BigDecimal value);

    default XContentGenerator writeNumber(short value) {
        return writeNumber((int) value);
    }

    XContentGenerator writeBoolean(boolean value);

    XContentGenerator writeNull();

    XContentGenerator writeBinary(byte[] value, int offset, int length);

    default XContentGenerator writeBinary(byte[] value) {
        if (value == null) {
            return writeNull();
        }
        return writeBinary(value, 0, value.length);
    }

    boolean isClosed();

    @Override
    void flush();

    @Override
    void close();

    default XContentGenerator startObject() {
        return writeStartObject();
    }

    default XContentGenerator startObject(String name) {
        writeFieldName(name);
        return writeStartObject();
    }

    default XContentGenerator endObject() {
        return writeEndObject();
    }

    default XContentGenerator startArray() {
        return writeStartArray();
    }

    default XContentGenerator startArray(String name) {
        writeFieldName(name);
        return writeStartArray();
    }

    default XContentGenerator endArray() {
        return writeEndArray();
    }

    default XContentGenerator field(String name, Object value) {
        writeFieldName(name);
        return writeValue(value);
    }

    default XContentGenerator nullField(String name) {
        writeFieldName(name);
        return writeNull();
    }

    default XContentGenerator writeMap(Map<?, ?> map) {
        if (map == null) {
            return writeNull();
        }
        writeStartObject(map.size());
        for (Map.Entry<?, ?> e : map.entrySet()) {
            writeFieldName(String.valueOf(e.getKey()));
            writeValue(e.getValue());
        }
        return writeEndObject();
    }

    default XContentGenerator writeIterable(Iterable<?> values) {
        if (values == null) {
            return writeNull();
        }
        if (values instanceof java.util.Collection<?> c) {
            writeStartArray(c.size());
        } else {
            writeStartArray();
        }
        for (Object v : values) {
            writeValue(v);
        }
        return writeEndArray();
    }

    default XContentGenerator writeValue(Object value) {
        if (value == null) {
            return writeNull();
        }
        if (value instanceof String s) {
            return writeString(s);
        }
        if (value instanceof Integer i) {
            return writeNumber(i.intValue());
        }
        if (value instanceof Long l) {
            return writeNumber(l.longValue());
        }
        if (value instanceof Double d) {
            return writeNumber(d.doubleValue());
        }
        if (value instanceof Float f) {
            return writeNumber(f.floatValue());
        }
        if (value instanceof Boolean b) {
            return writeBoolean(b);
        }
        if (value instanceof Map<?, ?> m) {
            return writeMap(m);
        }
        if (value instanceof ToXContent tx) {
            if (tx.isFragment()) {
                writeStartObject();
                tx.toXContent(this, ToXContent.EMPTY_PARAMS);
                return writeEndObject();
            }
            tx.toXContent(this, ToXContent.EMPTY_PARAMS);
            return this;
        }
        if (value instanceof Short s) {
            return writeNumber(s.shortValue());
        }
        if (value instanceof Byte b) {
            return writeNumber(b.intValue());
        }
        if (value instanceof BigInteger bi) {
            return writeNumber(bi);
        }
        if (value instanceof BigDecimal bd) {
            return writeNumber(bd);
        }
        if (value instanceof AtomicInteger ai) {
            return writeNumber(ai.get());
        }
        if (value instanceof AtomicLong al) {
            return writeNumber(al.get());
        }
        if (value instanceof Number n) {
            if (n.doubleValue() == n.longValue()) {
                return writeNumber(n.longValue());
            }
            return writeNumber(n.doubleValue());
        }
        if (value instanceof CharSequence cs) {
            return writeString(cs.toString());
        }
        if (value instanceof Character c) {
            return writeString(c.toString());
        }
        if (value instanceof byte[] bytes) {
            return writeBinary(bytes);
        }
        if (value instanceof Path p) {
            return writeString(p.toString());
        }
        if (value instanceof Iterable<?> it) {
            return writeIterable(it);
        }
        if (value instanceof Iterator<?> it) {
            writeStartArray();
            while (it.hasNext()) {
                writeValue(it.next());
            }
            return writeEndArray();
        }
        if (value instanceof Object[] arr) {
            writeStartArray(arr.length);
            for (Object o : arr) {
                writeValue(o);
            }
            return writeEndArray();
        }
        if (value instanceof int[] arr) {
            writeStartArray(arr.length);
            for (int v : arr) {
                writeNumber(v);
            }
            return writeEndArray();
        }
        if (value instanceof long[] arr) {
            writeStartArray(arr.length);
            for (long v : arr) {
                writeNumber(v);
            }
            return writeEndArray();
        }
        if (value instanceof double[] arr) {
            writeStartArray(arr.length);
            for (double v : arr) {
                writeNumber(v);
            }
            return writeEndArray();
        }
        if (value instanceof float[] arr) {
            writeStartArray(arr.length);
            for (float v : arr) {
                writeNumber(v);
            }
            return writeEndArray();
        }
        if (value instanceof short[] arr) {
            writeStartArray(arr.length);
            for (short v : arr) {
                writeNumber(v);
            }
            return writeEndArray();
        }
        if (value instanceof boolean[] arr) {
            writeStartArray(arr.length);
            for (boolean v : arr) {
                writeBoolean(v);
            }
            return writeEndArray();
        }
        if (value instanceof char[] arr) {
            return writeString(new String(arr));
        }
        if (value instanceof Optional<?> opt) {
            return writeValue(opt.orElse(null));
        }
        if (value instanceof Instant instant) {
            return writeString(DEFAULT_DATE_PRINTER.format(instant));
        }
        if (value instanceof ZonedDateTime zdt) {
            return writeString(DEFAULT_DATE_PRINTER.withZone(zdt.getZone()).format(zdt));
        }
        if (value instanceof OffsetDateTime odt) {
            return writeString(DEFAULT_DATE_PRINTER.withZone(odt.getOffset()).format(odt));
        }
        if (value instanceof Date date) {
            return writeString(DEFAULT_DATE_PRINTER.format(date.toInstant()));
        }
        if (value instanceof Calendar cal) {
            return writeString(DEFAULT_DATE_PRINTER.format(cal.toInstant()));
        }
        if (value instanceof LocalDateTime || value instanceof LocalDate || value instanceof LocalTime) {
            return writeString(value.toString());
        }
        if (value instanceof Enum<?> e) {
            return writeString(e.toString());
        }
        return writeString(value.toString());
    }

    default XContentGenerator copyCurrentEvent(XContentParser parser) {
        XContentParser.Token token = parser.currentToken();
        if (token == null) {
            throw new IllegalStateException("No current event to copy");
        }
        switch (token) {
            case START_OBJECT -> writeStartObject();
            case END_OBJECT -> writeEndObject();
            case START_ARRAY -> writeStartArray();
            case END_ARRAY -> writeEndArray();
            case FIELD_NAME -> writeFieldName(parser.currentName());
            case VALUE_STRING -> writeString(parser.text());
            case VALUE_NUMBER -> {
                switch (parser.numberType()) {
                    case INT -> writeNumber(parser.intValue());
                    case LONG -> writeNumber(parser.longValue());
                    case BIG_INTEGER -> writeNumber(parser.bigIntegerValue());
                    case FLOAT -> writeNumber(parser.floatValue());
                    case DOUBLE -> writeNumber(parser.doubleValue());
                    case BIG_DECIMAL -> writeNumber(parser.decimalValue());
                }
            }
            case VALUE_BOOLEAN -> writeBoolean(parser.booleanValue());
            case VALUE_NULL -> writeNull();
            case VALUE_EMBEDDED_OBJECT -> writeBinary(parser.binaryValue());
        }
        return this;
    }

    default XContentGenerator copyCurrentStructure(XContentParser parser) {
        XContentParser.Token token = parser.currentToken();
        if (token == null) {
            token = parser.nextToken();
            if (token == null) {
                return this;
            }
        }
        if (token == XContentParser.Token.FIELD_NAME) {
            writeFieldName(parser.currentName());
            token = parser.nextToken();
        }
        if (token == XContentParser.Token.START_OBJECT || token == XContentParser.Token.START_ARRAY) {
            int depth = 0;
            do {
                XContentParser.Token t = parser.currentToken();
                copyCurrentEvent(parser);
                if (t == XContentParser.Token.START_OBJECT || t == XContentParser.Token.START_ARRAY) {
                    depth++;
                } else if (t == XContentParser.Token.END_OBJECT || t == XContentParser.Token.END_ARRAY) {
                    depth--;
                }
                if (depth > 0) {
                    if (parser.nextToken() == null) {
                        throw new XContentParseException(parser.getTokenLocation(), "Unexpected end of content");
                    }
                }
            } while (depth > 0);
            return this;
        }
        return copyCurrentEvent(parser);
    }
}
