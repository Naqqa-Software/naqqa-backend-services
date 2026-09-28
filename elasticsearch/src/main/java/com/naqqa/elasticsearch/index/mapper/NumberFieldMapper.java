package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;

import java.math.BigInteger;
import java.util.Set;

public final class NumberFieldMapper extends FieldMapper {

    public enum NumberType {
        LONG, INTEGER, SHORT, BYTE, DOUBLE, FLOAT, HALF_FLOAT, SCALED_FLOAT, UNSIGNED_LONG;

        public String typeName() {
            return switch (this) {
                case LONG -> "long";
                case INTEGER -> "integer";
                case SHORT -> "short";
                case BYTE -> "byte";
                case DOUBLE -> "double";
                case FLOAT -> "float";
                case HALF_FLOAT -> "half_float";
                case SCALED_FLOAT -> "scaled_float";
                case UNSIGNED_LONG -> "unsigned_long";
            };
        }
    }

    private static final BigInteger UNSIGNED_LONG_MAX = new BigInteger("18446744073709551615");
    private static final BigInteger TWO_POW_63 = BigInteger.ONE.shiftLeft(63);

    private final NumberType numberType;
    private final boolean indexed;
    private final boolean docValues;
    private final boolean stored;
    private final boolean coerce;
    private final boolean ignoreMalformed;
    private final Double nullValue;
    private final double scalingFactor;

    private NumberFieldMapper(String simpleName, String fullPath, JsonObject node, NumberType numberType, boolean indexed,
                               boolean docValues, boolean stored, boolean coerce, boolean ignoreMalformed, Double nullValue,
                               double scalingFactor) {
        super(simpleName, fullPath, node);
        this.numberType = numberType;
        this.indexed = indexed;
        this.docValues = docValues;
        this.stored = stored;
        this.coerce = coerce;
        this.ignoreMalformed = ignoreMalformed;
        this.nullValue = nullValue;
        this.scalingFactor = scalingFactor;
    }

    public static TypeParser typeParser(NumberType type) {
        return (name, fullPath, node, ctx, depth) -> {
            boolean indexed = getBool(node, "index", true);
            boolean docValues = getBool(node, "doc_values", true);
            boolean stored = getBool(node, "store", false);
            boolean coerce = getBool(node, "coerce", true);
            boolean ignoreMalformed = getBool(node, "ignore_malformed", false);
            JsonValue nv = node.get("null_value");
            Double nullValue = nv == null || nv.isNull() ? null : nv.asDouble();
            double scalingFactor = type == NumberType.SCALED_FLOAT ? node.getDouble("scaling_factor", 1.0) : 1.0;
            NumberFieldMapper m = new NumberFieldMapper(name, fullPath, node, type, indexed, docValues, stored, coerce, ignoreMalformed, nullValue, scalingFactor);
            m.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
            return m;
        };
    }

    @Override
    public String typeName() {
        return numberType.typeName();
    }

    public NumberType numberType() {
        return numberType;
    }

    public double scalingFactor() {
        return scalingFactor;
    }

    @Override
    protected boolean ignoreMalformed() {
        return ignoreMalformed;
    }

    @Override
    protected Object nullValue() {
        return nullValue;
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        if (numberType == NumberType.UNSIGNED_LONG) {
            BigInteger big = toUnsignedLong(value);
            long encoded = big.subtract(TWO_POW_63).longValue();
            if (docValues) {
                context.addIndexableField(IndexableField.numericDocValue(fullPath, encoded));
            }
            if (indexed) {
                context.addIndexableField(IndexableField.point(fullPath, new byte[][] {NumericUtils.longToSortableBytes(encoded)}));
            }
            if (stored) {
                context.addIndexableField(IndexableField.stored(fullPath, big.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            return;
        }
        double d = toDouble(value);
        long longDocValue;
        byte[] pointBytes;
        byte[] storedBytes;
        switch (numberType) {
            case BYTE, SHORT, INTEGER, LONG -> {
                long whole = coerceToLong(d);
                checkBounds(whole);
                longDocValue = whole;
                pointBytes = numberType == NumberType.LONG
                    ? NumericUtils.longToSortableBytes(whole)
                    : NumericUtils.intToSortableBytes((int) whole);
                storedBytes = Long.toString(whole).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            case DOUBLE -> {
                longDocValue = NumericUtils.doubleToSortableLong(d);
                pointBytes = NumericUtils.doubleToSortableBytes(d);
                storedBytes = Double.toString(d).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            case FLOAT -> {
                float f = (float) d;
                longDocValue = NumericUtils.floatToSortableInt(f);
                pointBytes = NumericUtils.floatToSortableBytes(f);
                storedBytes = Float.toString(f).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            case HALF_FLOAT -> {
                float f = NumericUtils.halfFloatRoundTrip((float) d);
                longDocValue = NumericUtils.floatToSortableInt(f);
                pointBytes = NumericUtils.floatToSortableBytes(f);
                storedBytes = Float.toString(f).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            case SCALED_FLOAT -> {
                long scaled = Math.round(d * scalingFactor);
                longDocValue = scaled;
                pointBytes = NumericUtils.longToSortableBytes(scaled);
                storedBytes = Double.toString(d).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            default -> throw new IllegalStateException();
        }
        if (docValues) {
            context.addIndexableField(IndexableField.numericDocValue(fullPath, longDocValue));
        }
        if (indexed) {
            context.addIndexableField(IndexableField.point(fullPath, new byte[][] {pointBytes}));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, storedBytes));
        }
    }

    private long coerceToLong(double d) {
        if (d != Math.floor(d) || Double.isInfinite(d)) {
            if (!coerce) {
                throw new NumberFormatException("Value [" + d + "] has a decimal part, but " + typeName() + " does not accept decimals");
            }
        }
        return (long) d;
    }

    private void checkBounds(long v) {
        long min;
        long max;
        switch (numberType) {
            case BYTE -> { min = Byte.MIN_VALUE; max = Byte.MAX_VALUE; }
            case SHORT -> { min = Short.MIN_VALUE; max = Short.MAX_VALUE; }
            case INTEGER -> { min = Integer.MIN_VALUE; max = Integer.MAX_VALUE; }
            default -> { min = Long.MIN_VALUE; max = Long.MAX_VALUE; }
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException("Value [" + v + "] is out of range for a " + typeName() + " field [" + fullPath + "]");
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (value instanceof String s) {
            return Double.parseDouble(s.trim());
        }
        throw new IllegalArgumentException("cannot parse number from [" + value + "]");
    }

    private static BigInteger toUnsignedLong(Object value) {
        BigInteger big;
        if (value instanceof BigInteger bi) {
            big = bi;
        } else if (value instanceof Number n) {
            big = new BigInteger(String.valueOf(n.longValue() >= 0 ? n.longValue() : n.toString()));
            if (n instanceof Double || n instanceof Float) {
                big = BigInteger.valueOf((long) n.doubleValue());
            }
        } else if (value instanceof String s) {
            big = new BigInteger(s.trim());
        } else {
            throw new IllegalArgumentException("cannot parse unsigned_long from [" + value + "]");
        }
        if (big.signum() < 0 || big.compareTo(UNSIGNED_LONG_MAX) > 0) {
            throw new IllegalArgumentException("Value [" + big + "] is out of range for an unsigned_long");
        }
        return big;
    }

    @Override
    protected Set<String> updatableParameters() {
        return Set.of("meta", "coerce", "ignore_malformed");
    }
}
