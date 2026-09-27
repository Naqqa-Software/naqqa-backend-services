package com.naqqa.elasticsearch.script.functions;

import com.naqqa.elasticsearch.script.DocLookup;
import com.naqqa.elasticsearch.script.FunctionContext;
import com.naqqa.elasticsearch.script.GeoPoint;
import com.naqqa.elasticsearch.script.ScriptDocValues;
import com.naqqa.elasticsearch.script.ScriptFunction;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ScoreScriptFunctions {

    private static final Map<String, ScriptFunction> ALL;

    static {
        Map<String, ScriptFunction> m = new LinkedHashMap<>();
        m.put("cosineSimilarity/2", (c, a) -> cosineSimilarity(queryVector(a[0]), docVector(c, a[1])));
        m.put("dotProduct/2", (c, a) -> dotProduct(queryVector(a[0]), docVector(c, a[1])));
        m.put("l1norm/2", (c, a) -> l1norm(queryVector(a[0]), docVector(c, a[1])));
        m.put("l2norm/2", (c, a) -> l2norm(queryVector(a[0]), docVector(c, a[1])));
        m.put("hamming/2", (c, a) -> hamming(queryVector(a[0]), docVector(c, a[1])));
        m.put("decayNumericLinear/5", (c, a) -> decayNumericLinear(num(a[0]), num(a[1]), num(a[2]), num(a[3]), num(a[4])));
        m.put("decayNumericExp/5", (c, a) -> decayNumericExp(num(a[0]), num(a[1]), num(a[2]), num(a[3]), num(a[4])));
        m.put("decayNumericGauss/5", (c, a) -> decayNumericGauss(num(a[0]), num(a[1]), num(a[2]), num(a[3]), num(a[4])));
        m.put("decayGeoLinear/5", (c, a) -> decayGeoLinear(a[0], str(a[1]), str(a[2]), num(a[3]), GeoPoint.parse(a[4])));
        m.put("decayGeoExp/5", (c, a) -> decayGeoExp(a[0], str(a[1]), str(a[2]), num(a[3]), GeoPoint.parse(a[4])));
        m.put("decayGeoGauss/5", (c, a) -> decayGeoGauss(a[0], str(a[1]), str(a[2]), num(a[3]), GeoPoint.parse(a[4])));
        m.put("decayDateLinear/5", (c, a) -> decayDateLinear(a[0], str(a[1]), str(a[2]), num(a[3]), a[4]));
        m.put("decayDateExp/5", (c, a) -> decayDateExp(a[0], str(a[1]), str(a[2]), num(a[3]), a[4]));
        m.put("decayDateGauss/5", (c, a) -> decayDateGauss(a[0], str(a[1]), str(a[2]), num(a[3]), a[4]));
        m.put("saturation/2", (c, a) -> saturation(num(a[0]), num(a[1])));
        m.put("sigmoid/3", (c, a) -> sigmoid(num(a[0]), num(a[1]), num(a[2])));
        m.put("randomScore/1", (c, a) -> randomScore(toInt(a[0]), docId(c)));
        m.put("randomScore/2", (c, a) -> randomScore(toInt(a[0]), fieldValue(c, str(a[1]))));
        ALL = Collections.unmodifiableMap(m);
    }

    private ScoreScriptFunctions() {
    }

    public static Map<String, ScriptFunction> all() {
        return ALL;
    }

    public static float[] queryVector(Object v) {
        if (v instanceof float[] f) {
            return f;
        }
        if (v instanceof double[] d) {
            float[] r = new float[d.length];
            for (int i = 0; i < d.length; i++) {
                r[i] = (float) d[i];
            }
            return r;
        }
        if (v instanceof int[] ia) {
            float[] r = new float[ia.length];
            for (int i = 0; i < ia.length; i++) {
                r[i] = ia[i];
            }
            return r;
        }
        if (v instanceof byte[] b) {
            float[] r = new float[b.length];
            for (int i = 0; i < b.length; i++) {
                r[i] = b[i];
            }
            return r;
        }
        if (v instanceof List<?> l) {
            float[] r = new float[l.size()];
            for (int i = 0; i < r.length; i++) {
                Object o = l.get(i);
                if (!(o instanceof Number n)) {
                    throw new IllegalArgumentException("query vector must contain only numbers but found [" + o + "]");
                }
                r[i] = n.floatValue();
            }
            return r;
        }
        if (v instanceof ScriptDocValues.DenseVector dv) {
            return dv.getVectorValue();
        }
        throw new IllegalArgumentException("unsupported query vector [" + v + "]");
    }

    public static float[] docVector(FunctionContext c, Object field) {
        if (field instanceof ScriptDocValues.DenseVector dv) {
            return dv.getVectorValue();
        }
        if (field instanceof CharSequence cs) {
            Object doc = c.variable("doc");
            if (!(doc instanceof DocLookup lookup)) {
                throw new IllegalArgumentException("vector functions require document access");
            }
            ScriptDocValues<?> values = lookup.get(cs.toString());
            if (values instanceof ScriptDocValues.DenseVector dv) {
                return dv.getVectorValue();
            }
            throw new IllegalArgumentException("field [" + cs + "] is not a dense_vector field");
        }
        return queryVector(field);
    }

    private static void checkDims(float[] q, float[] d) {
        if (q.length != d.length) {
            throw new IllegalArgumentException("The query vector has a different number of dimensions [" + q.length + "] than the document vectors [" + d.length + "].");
        }
    }

    public static double dotProduct(float[] q, float[] d) {
        checkDims(q, d);
        double s = 0;
        for (int i = 0; i < q.length; i++) {
            s += (double) q[i] * d[i];
        }
        return s;
    }

    public static double cosineSimilarity(float[] q, float[] d) {
        checkDims(q, d);
        double dot = 0;
        double qn = 0;
        double dn = 0;
        for (int i = 0; i < q.length; i++) {
            dot += (double) q[i] * d[i];
            qn += (double) q[i] * q[i];
            dn += (double) d[i] * d[i];
        }
        if (qn == 0 || dn == 0) {
            throw new IllegalArgumentException("The cosine similarity is undefined for zero-magnitude vectors");
        }
        return dot / (Math.sqrt(qn) * Math.sqrt(dn));
    }

    public static double l1norm(float[] q, float[] d) {
        checkDims(q, d);
        double s = 0;
        for (int i = 0; i < q.length; i++) {
            s += Math.abs((double) q[i] - d[i]);
        }
        return s;
    }

    public static double l2norm(float[] q, float[] d) {
        checkDims(q, d);
        double s = 0;
        for (int i = 0; i < q.length; i++) {
            double diff = (double) q[i] - d[i];
            s += diff * diff;
        }
        return Math.sqrt(s);
    }

    public static int hamming(float[] q, float[] d) {
        checkDims(q, d);
        int s = 0;
        for (int i = 0; i < q.length; i++) {
            s += Integer.bitCount(((byte) q[i] ^ (byte) d[i]) & 0xFF);
        }
        return s;
    }

    public static double decayNumericLinear(double origin, double scale, double offset, double decay, double value) {
        double scaling = scale / (1.0 - decay);
        double distance = Math.max(0.0, Math.abs(value - origin) - offset);
        return Math.max(0.0, (scaling - distance) / scaling);
    }

    public static double decayNumericExp(double origin, double scale, double offset, double decay, double value) {
        double scaling = Math.log(decay) / scale;
        double distance = Math.max(0.0, Math.abs(value - origin) - offset);
        return Math.exp(scaling * distance);
    }

    public static double decayNumericGauss(double origin, double scale, double offset, double decay, double value) {
        double scaling = 0.5 * Math.pow(scale, 2.0) / Math.log(decay);
        double distance = Math.max(0.0, Math.abs(value - origin) - offset);
        return Math.exp(0.5 * Math.pow(distance, 2.0) / scaling);
    }

    public static double decayGeoLinear(Object origin, String scale, String offset, double decay, GeoPoint value) {
        GeoPoint o = GeoPoint.parse(origin);
        return decayNumericLinear(0, parseDistanceMeters(scale), parseDistanceMeters(offset), decay, value.arcDistance(o));
    }

    public static double decayGeoExp(Object origin, String scale, String offset, double decay, GeoPoint value) {
        GeoPoint o = GeoPoint.parse(origin);
        return decayNumericExp(0, parseDistanceMeters(scale), parseDistanceMeters(offset), decay, value.arcDistance(o));
    }

    public static double decayGeoGauss(Object origin, String scale, String offset, double decay, GeoPoint value) {
        GeoPoint o = GeoPoint.parse(origin);
        return decayNumericGauss(0, parseDistanceMeters(scale), parseDistanceMeters(offset), decay, value.arcDistance(o));
    }

    public static double decayDateLinear(Object origin, String scale, String offset, double decay, Object value) {
        return decayNumericLinear(0, parseTimeMillis(scale), parseTimeMillis(offset), decay, Math.abs(toMillis(value) - toMillis(origin)));
    }

    public static double decayDateExp(Object origin, String scale, String offset, double decay, Object value) {
        return decayNumericExp(0, parseTimeMillis(scale), parseTimeMillis(offset), decay, Math.abs(toMillis(value) - toMillis(origin)));
    }

    public static double decayDateGauss(Object origin, String scale, String offset, double decay, Object value) {
        return decayNumericGauss(0, parseTimeMillis(scale), parseTimeMillis(offset), decay, Math.abs(toMillis(value) - toMillis(origin)));
    }

    public static double saturation(double value, double k) {
        return value / (k + value);
    }

    public static double sigmoid(double value, double k, double a) {
        double va = Math.pow(value, a);
        return va / (Math.pow(k, a) + va);
    }

    public static double randomScore(int seed, Object key) {
        int hash = key instanceof Integer i ? mix32(i) : murmur(String.valueOf(key));
        int h = mix32(hash ^ mix32(seed));
        return (h & 0x00FFFFFF) / (double) (1 << 24);
    }

    private static int mix32(int k) {
        k ^= k >>> 16;
        k *= 0x85ebca6b;
        k ^= k >>> 13;
        k *= 0xc2b2ae35;
        k ^= k >>> 16;
        return k;
    }

    private static int murmur(String s) {
        int h = 0x9747b28c;
        for (int i = 0; i < s.length(); i++) {
            int k = s.charAt(i);
            k *= 0xcc9e2d51;
            k = Integer.rotateLeft(k, 15);
            k *= 0x1b873593;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
        }
        h ^= s.length();
        return mix32(h);
    }

    private static Object docId(FunctionContext c) {
        Object doc = c.variable("doc");
        if (doc instanceof DocLookup lookup) {
            return lookup.docId();
        }
        return 0;
    }

    private static Object fieldValue(FunctionContext c, String field) {
        Object doc = c.variable("doc");
        if (doc instanceof DocLookup lookup) {
            ScriptDocValues<?> v = lookup.get(field);
            return v.isEmpty() ? "" : String.valueOf(v.get(0));
        }
        throw new IllegalArgumentException("randomScore with a field requires document access");
    }

    public static double parseDistanceMeters(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < t.length() && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.' || t.charAt(i) == '-' || t.charAt(i) == 'e' && i > 0 && i + 1 < t.length() && (Character.isDigit(t.charAt(i + 1)) || t.charAt(i + 1) == '-'))) {
            i++;
        }
        double v = Double.parseDouble(t.substring(0, i));
        String unit = t.substring(i).trim();
        double factor = switch (unit) {
            case "", "m", "meters", "meter" -> 1.0;
            case "km", "kilometers", "kilometer" -> 1000.0;
            case "cm", "centimeters" -> 0.01;
            case "mm", "millimeters" -> 0.001;
            case "mi", "miles", "mile" -> 1609.344;
            case "yd", "yards" -> 0.9144;
            case "ft", "feet" -> 0.3048;
            case "in", "inch" -> 0.0254;
            case "nmi", "nm", "nauticalmiles" -> 1852.0;
            default -> throw new IllegalArgumentException("unknown distance unit [" + unit + "] in [" + s + "]");
        };
        return v * factor;
    }

    public static double parseTimeMillis(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < t.length() && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.' || t.charAt(i) == '-')) {
            i++;
        }
        double v = Double.parseDouble(t.substring(0, i));
        String unit = t.substring(i).trim();
        double factor = switch (unit) {
            case "", "ms" -> 1.0;
            case "nanos" -> 1e-6;
            case "micros" -> 1e-3;
            case "s" -> 1000.0;
            case "m" -> 60_000.0;
            case "h" -> 3_600_000.0;
            case "d" -> 86_400_000.0;
            case "w" -> 7 * 86_400_000.0;
            default -> throw new IllegalArgumentException("unknown time unit [" + unit + "] in [" + s + "]");
        };
        return v * factor;
    }

    public static long toMillis(Object v) {
        if (v instanceof ZonedDateTime z) {
            return z.toInstant().toEpochMilli();
        }
        if (v instanceof OffsetDateTime o) {
            return o.toInstant().toEpochMilli();
        }
        if (v instanceof Instant i) {
            return i.toEpochMilli();
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof CharSequence cs) {
            String s = cs.toString().trim();
            try {
                return ZonedDateTime.parse(s).toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                try {
                    return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (DateTimeParseException e2) {
                    try {
                        return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                    } catch (DateTimeParseException e3) {
                        try {
                            return Long.parseLong(s);
                        } catch (NumberFormatException e4) {
                            throw new IllegalArgumentException("cannot parse date [" + s + "]");
                        }
                    }
                }
            }
        }
        throw new IllegalArgumentException("cannot convert [" + v + "] to a date");
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof ZonedDateTime z) {
            return z.toInstant().toEpochMilli();
        }
        if (o instanceof CharSequence cs) {
            return Double.parseDouble(cs.toString());
        }
        throw new IllegalArgumentException("expected a number but got [" + o + "]");
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        throw new IllegalArgumentException("expected an integer seed but got [" + o + "]");
    }

    private static String str(Object o) {
        if (o == null) {
            throw new IllegalArgumentException("expected a string but got null");
        }
        return o.toString();
    }
}
