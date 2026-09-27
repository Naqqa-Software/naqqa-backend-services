package com.naqqa.elasticsearch.common.xcontent;

import java.io.Closeable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

public interface XContentParser extends Closeable {

    enum Token {
        START_OBJECT,
        END_OBJECT,
        START_ARRAY,
        END_ARRAY,
        FIELD_NAME,
        VALUE_STRING,
        VALUE_NUMBER,
        VALUE_BOOLEAN,
        VALUE_EMBEDDED_OBJECT,
        VALUE_NULL;

        public boolean isValue() {
            return this == VALUE_STRING || this == VALUE_NUMBER || this == VALUE_BOOLEAN
                || this == VALUE_EMBEDDED_OBJECT || this == VALUE_NULL;
        }

        public boolean isStructStart() {
            return this == START_OBJECT || this == START_ARRAY;
        }

        public boolean isStructEnd() {
            return this == END_OBJECT || this == END_ARRAY;
        }
    }

    enum NumberType {
        INT, LONG, BIG_INTEGER, FLOAT, DOUBLE, BIG_DECIMAL
    }

    XContentType contentType();

    Token nextToken();

    Token currentToken();

    String currentName();

    default String nextFieldName() {
        if (nextToken() == Token.FIELD_NAME) {
            return currentName();
        }
        return null;
    }

    void skipChildren();

    String text();

    String textOrNull();

    Object objectText();

    Number numberValue();

    NumberType numberType();

    short shortValue(boolean coerce);

    default short shortValue() {
        return shortValue(true);
    }

    int intValue(boolean coerce);

    default int intValue() {
        return intValue(true);
    }

    long longValue(boolean coerce);

    default long longValue() {
        return longValue(true);
    }

    float floatValue(boolean coerce);

    default float floatValue() {
        return floatValue(true);
    }

    double doubleValue(boolean coerce);

    default double doubleValue() {
        return doubleValue(true);
    }

    BigInteger bigIntegerValue();

    BigDecimal decimalValue();

    boolean isBooleanValue();

    boolean booleanValue();

    byte[] binaryValue();

    Map<String, Object> map();

    Map<String, Object> mapOrdered();

    Map<String, String> mapStrings();

    List<Object> list();

    List<Object> listOrderedMap();

    Object readValue();

    XContentLocation getTokenLocation();

    boolean isClosed();

    @Override
    void close();
}
