package com.naqqa.elasticsearch.script;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class ScriptDocValues<T> extends AbstractList<T> {

    protected final List<T> values;

    protected ScriptDocValues(List<T> values) {
        this.values = values == null ? List.of() : values;
    }

    public T getValue() {
        if (values.isEmpty()) {
            throw new IllegalStateException("A document doesn't have a value for a field! Use doc[<field>].size()==0 to check if a document is missing a field!");
        }
        return values.get(0);
    }

    public List<T> getValues() {
        return Collections.unmodifiableList(values);
    }

    @Override
    public T get(int index) {
        if (index < 0 || index >= values.size()) {
            throw new IndexOutOfBoundsException("A document doesn't have a value for a field at position [" + index + "]!");
        }
        return values.get(index);
    }

    @Override
    public int size() {
        return values.size();
    }

    public int getLength() {
        return values.size();
    }

    public boolean getEmpty() {
        return values.isEmpty();
    }

    public static Longs longs(long... v) {
        List<Long> l = new ArrayList<>(v.length);
        for (long x : v) {
            l.add(x);
        }
        return new Longs(l);
    }

    public static Doubles doubles(double... v) {
        List<Double> l = new ArrayList<>(v.length);
        for (double x : v) {
            l.add(x);
        }
        return new Doubles(l);
    }

    public static Strings strings(String... v) {
        return new Strings(List.of(v));
    }

    public static Booleans booleans(boolean... v) {
        List<Boolean> l = new ArrayList<>(v.length);
        for (boolean x : v) {
            l.add(x);
        }
        return new Booleans(l);
    }

    public static Dates dates(ZonedDateTime... v) {
        return new Dates(List.of(v));
    }

    public static Dates datesMillis(long... millis) {
        List<ZonedDateTime> l = new ArrayList<>(millis.length);
        for (long m : millis) {
            l.add(ZonedDateTime.ofInstant(Instant.ofEpochMilli(m), ZoneOffset.UTC));
        }
        return new Dates(l);
    }

    public static GeoPoints geoPoints(GeoPoint... v) {
        return new GeoPoints(List.of(v));
    }

    public static DenseVector denseVector(float[] vector) {
        return new DenseVector(vector);
    }

    public static final class Longs extends ScriptDocValues<Long> {
        public Longs(List<Long> values) {
            super(values);
        }
    }

    public static final class Doubles extends ScriptDocValues<Double> {
        public Doubles(List<Double> values) {
            super(values);
        }
    }

    public static final class Strings extends ScriptDocValues<String> {
        public Strings(List<String> values) {
            super(values);
        }
    }

    public static final class Booleans extends ScriptDocValues<Boolean> {
        public Booleans(List<Boolean> values) {
            super(values);
        }
    }

    public static final class Dates extends ScriptDocValues<ZonedDateTime> {
        public Dates(List<ZonedDateTime> values) {
            super(values);
        }

        public ZonedDateTime getDate() {
            return getValue();
        }

        public List<ZonedDateTime> getDates() {
            return getValues();
        }

        public long getMillis() {
            return getValue().toInstant().toEpochMilli();
        }
    }

    public static final class GeoPoints extends ScriptDocValues<GeoPoint> {
        public GeoPoints(List<GeoPoint> values) {
            super(values);
        }

        public double getLat() {
            return getValue().lat();
        }

        public double getLon() {
            return getValue().lon();
        }

        public double[] getLats() {
            double[] r = new double[values.size()];
            for (int i = 0; i < r.length; i++) {
                r[i] = values.get(i).lat();
            }
            return r;
        }

        public double[] getLons() {
            double[] r = new double[values.size()];
            for (int i = 0; i < r.length; i++) {
                r[i] = values.get(i).lon();
            }
            return r;
        }

        public double arcDistance(double lat, double lon) {
            return getValue().arcDistance(lat, lon);
        }

        public double arcDistanceWithDefault(double lat, double lon, double defaultValue) {
            return values.isEmpty() ? defaultValue : arcDistance(lat, lon);
        }

        public GeoPoint getCentroid() {
            if (values.isEmpty()) {
                return null;
            }
            double lat = 0;
            double lon = 0;
            for (GeoPoint p : values) {
                lat += p.lat();
                lon += p.lon();
            }
            return new GeoPoint(lat / values.size(), lon / values.size());
        }
    }

    public static final class DenseVector extends ScriptDocValues<float[]> {
        private final float[] vector;

        public DenseVector(float[] vector) {
            super(vector == null ? List.of() : Collections.singletonList(vector));
            this.vector = vector;
        }

        public float[] getVectorValue() {
            if (vector == null) {
                throw new IllegalArgumentException("A document doesn't have a value for a vector field!");
            }
            return vector;
        }

        public float getMagnitude() {
            float[] v = getVectorValue();
            double s = 0;
            for (float f : v) {
                s += (double) f * f;
            }
            return (float) Math.sqrt(s);
        }

        public int getDims() {
            return vector == null ? 0 : vector.length;
        }
    }
}
