package com.naqqa.elasticsearch.common.xcontent;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractXContentParser implements XContentParser {

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    protected XContentParseException parseError(String message) {
        return new XContentParseException(getTokenLocation(), message);
    }

    protected abstract boolean doBooleanValue();

    @Override
    public void skipChildren() {
        Token token = currentToken();
        if (token != Token.START_OBJECT && token != Token.START_ARRAY) {
            return;
        }
        int depth = 1;
        while (depth > 0) {
            Token t = nextToken();
            if (t == null) {
                throw parseError("Unexpected end of content while skipping children");
            }
            if (t == Token.START_OBJECT || t == Token.START_ARRAY) {
                depth++;
            } else if (t == Token.END_OBJECT || t == Token.END_ARRAY) {
                depth--;
            }
        }
    }

    @Override
    public String textOrNull() {
        if (currentToken() == Token.VALUE_NULL) {
            return null;
        }
        return text();
    }

    @Override
    public Object objectText() {
        Token token = currentToken();
        if (token == Token.VALUE_STRING) {
            return text();
        } else if (token == Token.VALUE_NUMBER) {
            return numberValue();
        } else if (token == Token.VALUE_BOOLEAN) {
            return doBooleanValue();
        } else if (token == Token.VALUE_NULL) {
            return null;
        } else if (token == Token.VALUE_EMBEDDED_OBJECT) {
            return binaryValue();
        }
        return text();
    }

    private void checkCoerceString(boolean coerce, String type) {
        if (!coerce) {
            throw new IllegalArgumentException(type + " value passed as String");
        }
    }

    private void ensureNumber() {
        Token token = currentToken();
        if (token != Token.VALUE_NUMBER && token != Token.VALUE_STRING) {
            throw new IllegalStateException("Current token (" + token + ") not numeric, can not use numeric value accessors");
        }
    }

    private BigDecimal stringAsDecimal() {
        String text = text().trim();
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(text);
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new IllegalArgumentException("Value [" + text + "] is not a finite number");
                }
                return BigDecimal.valueOf(d);
            } catch (NumberFormatException e2) {
                throw new IllegalArgumentException("For input string: \"" + text + "\"", e2);
            }
        }
    }

    private long toLong(BigDecimal value, boolean coerce, String type, long min, long max) {
        if (!coerce && value.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Value [" + text() + "] has a decimal part");
        }
        BigInteger bi = value.toBigInteger();
        if (bi.compareTo(BigInteger.valueOf(min)) < 0 || bi.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new IllegalArgumentException("Value [" + text() + "] is out of range for " + type);
        }
        return bi.longValue();
    }

    private long integralValue(boolean coerce, String type, long min, long max) {
        ensureNumber();
        if (currentToken() == Token.VALUE_STRING) {
            checkCoerceString(coerce, type);
            String text = text().trim();
            long v;
            try {
                v = Long.parseLong(text);
            } catch (NumberFormatException e) {
                return toLong(stringAsDecimal(), coerce, type, min, max);
            }
            if (v < min || v > max) {
                throw new IllegalArgumentException("Value [" + text + "] is out of range for " + type);
            }
            return v;
        }
        NumberType nt = numberType();
        Number n = numberValue();
        if (nt == NumberType.INT || nt == NumberType.LONG) {
            long v = n.longValue();
            if (v < min || v > max) {
                throw parseError("Numeric value (" + text() + ") out of range of " + type);
            }
            return v;
        }
        if (nt == NumberType.BIG_INTEGER) {
            BigInteger bi = (BigInteger) n;
            if (bi.compareTo(LONG_MIN) < 0 || bi.compareTo(LONG_MAX) > 0 || bi.longValue() < min || bi.longValue() > max) {
                throw parseError("Numeric value (" + text() + ") out of range of " + type);
            }
            return bi.longValue();
        }
        BigDecimal bd;
        if (n instanceof BigDecimal b) {
            bd = b;
        } else {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("Value [" + d + "] is out of range for " + type);
            }
            bd = new BigDecimal(d);
        }
        return toLong(bd, coerce, type, min, max);
    }

    @Override
    public short shortValue(boolean coerce) {
        return (short) integralValue(coerce, "a short", Short.MIN_VALUE, Short.MAX_VALUE);
    }

    @Override
    public int intValue(boolean coerce) {
        return (int) integralValue(coerce, "an integer", Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    @Override
    public long longValue(boolean coerce) {
        return integralValue(coerce, "a long", Long.MIN_VALUE, Long.MAX_VALUE);
    }

    @Override
    public float floatValue(boolean coerce) {
        ensureNumber();
        if (currentToken() == Token.VALUE_STRING) {
            checkCoerceString(coerce, "Float");
            try {
                return Float.parseFloat(text().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("For input string: \"" + text() + "\"", e);
            }
        }
        return numberValue().floatValue();
    }

    @Override
    public double doubleValue(boolean coerce) {
        ensureNumber();
        if (currentToken() == Token.VALUE_STRING) {
            checkCoerceString(coerce, "Double");
            try {
                return Double.parseDouble(text().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("For input string: \"" + text() + "\"", e);
            }
        }
        return numberValue().doubleValue();
    }

    @Override
    public BigInteger bigIntegerValue() {
        ensureNumber();
        if (currentToken() == Token.VALUE_STRING) {
            return stringAsDecimal().toBigInteger();
        }
        Number n = numberValue();
        if (n instanceof BigInteger bi) {
            return bi;
        }
        if (n instanceof BigDecimal bd) {
            return bd.toBigInteger();
        }
        if (n instanceof Double || n instanceof Float) {
            return new BigDecimal(n.doubleValue()).toBigInteger();
        }
        return BigInteger.valueOf(n.longValue());
    }

    @Override
    public BigDecimal decimalValue() {
        ensureNumber();
        if (currentToken() == Token.VALUE_STRING) {
            return stringAsDecimal();
        }
        Number n = numberValue();
        if (n instanceof BigDecimal bd) {
            return bd;
        }
        if (n instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (n instanceof Double || n instanceof Float) {
            return new BigDecimal(n.toString());
        }
        return BigDecimal.valueOf(n.longValue());
    }

    @Override
    public boolean isBooleanValue() {
        Token token = currentToken();
        if (token == Token.VALUE_BOOLEAN) {
            return true;
        }
        if (token == Token.VALUE_STRING) {
            String t = text();
            return t.equals("true") || t.equals("false");
        }
        return false;
    }

    @Override
    public boolean booleanValue() {
        Token token = currentToken();
        if (token == Token.VALUE_STRING) {
            String t = text();
            if (t.equals("true")) {
                return true;
            }
            if (t.equals("false")) {
                return false;
            }
            throw new IllegalArgumentException("Failed to parse value [" + t + "] as only [true] or [false] are allowed.");
        }
        if (token != Token.VALUE_BOOLEAN) {
            throw new IllegalStateException("Current token (" + token + ") not of boolean type");
        }
        return doBooleanValue();
    }

    @Override
    public byte[] binaryValue() {
        if (currentToken() == Token.VALUE_STRING) {
            try {
                return Base64.getMimeDecoder().decode(text());
            } catch (IllegalArgumentException e) {
                throw parseError("Failed to decode VALUE_STRING as base64: " + e.getMessage());
            }
        }
        throw new IllegalStateException("Current token (" + currentToken() + ") not VALUE_EMBEDDED_OBJECT or VALUE_STRING");
    }

    @Override
    public Map<String, Object> map() {
        return readMap();
    }

    @Override
    public Map<String, Object> mapOrdered() {
        return readMap();
    }

    @Override
    public Map<String, String> mapStrings() {
        Map<String, Object> raw = readMap();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            result.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return result;
    }

    @Override
    public List<Object> list() {
        return readList();
    }

    @Override
    public List<Object> listOrderedMap() {
        return readList();
    }

    @Override
    public Object readValue() {
        Token token = currentToken();
        if (token == null) {
            token = nextToken();
        }
        if (token == Token.FIELD_NAME) {
            token = nextToken();
        }
        if (token == null) {
            throw parseError("Unexpected end of content");
        }
        return readValueForToken(token);
    }

    private Object readValueForToken(Token token) {
        switch (token) {
            case START_OBJECT:
                return readMap();
            case START_ARRAY:
                return readListFromStart();
            case VALUE_STRING:
                return text();
            case VALUE_NUMBER:
                return numberValue();
            case VALUE_BOOLEAN:
                return doBooleanValue();
            case VALUE_NULL:
                return null;
            case VALUE_EMBEDDED_OBJECT:
                return binaryValue();
            default:
                throw parseError("Unexpected token [" + token + "] while reading value");
        }
    }

    protected Map<String, Object> readMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        Token token = currentToken();
        if (token == null) {
            token = nextToken();
        }
        if (token == Token.START_OBJECT) {
            token = nextToken();
        } else if (token != Token.FIELD_NAME && token != Token.END_OBJECT) {
            throw parseError("Failed to parse object: expecting token of type [START_OBJECT] but found [" + token + "]");
        }
        while (token == Token.FIELD_NAME) {
            String name = currentName();
            Token valueToken = nextToken();
            if (valueToken == null) {
                throw parseError("Unexpected end of content");
            }
            map.put(name, readValueForToken(valueToken));
            token = nextToken();
        }
        if (token != Token.END_OBJECT) {
            throw parseError("Failed to parse object: expecting token of type [END_OBJECT] but found [" + token + "]");
        }
        return map;
    }

    protected List<Object> readList() {
        Token token = currentToken();
        if (token == null) {
            token = nextToken();
        }
        if (token == Token.FIELD_NAME) {
            token = nextToken();
        }
        if (token != Token.START_ARRAY) {
            throw parseError("Failed to parse list:  expecting [START_ARRAY] but got [" + token + "]");
        }
        return readListFromStart();
    }

    private List<Object> readListFromStart() {
        List<Object> list = new ArrayList<>();
        Token token;
        while ((token = nextToken()) != Token.END_ARRAY) {
            if (token == null) {
                throw parseError("Unexpected end of content while reading array");
            }
            list.add(readValueForToken(token));
        }
        return list;
    }
}
