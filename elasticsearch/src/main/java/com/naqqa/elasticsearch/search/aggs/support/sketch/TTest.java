package com.naqqa.elasticsearch.search.aggs.support.sketch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class TTest {

    public enum Type {
        PAIRED,
        HOMOSCEDASTIC,
        HETEROSCEDASTIC
    }

    public record Result(double t, double degreesOfFreedom, double pValue) {
    }

    private static final int SERIAL_VERSION = 1;

    private final Type type;
    private final int tails;
    private final TTestStats a;
    private final TTestStats b;

    public TTest(Type type, int tails) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        checkTails(tails);
        this.type = type;
        this.tails = tails;
        this.a = new TTestStats();
        this.b = new TTestStats();
    }

    private static void checkTails(int tails) {
        if (tails != 1 && tails != 2) {
            throw new IllegalArgumentException("tails must be 1 or 2, got " + tails);
        }
    }

    public Type type() {
        return type;
    }

    public int tails() {
        return tails;
    }

    public TTestStats statsA() {
        return a;
    }

    public TTestStats statsB() {
        return b;
    }

    public void addPair(double valueA, double valueB) {
        if (type != Type.PAIRED) {
            throw new IllegalStateException("addPair is only supported for paired t-tests");
        }
        a.add(valueA - valueB);
    }

    public void addA(double value) {
        if (type == Type.PAIRED) {
            throw new IllegalStateException("paired t-test requires addPair");
        }
        a.add(value);
    }

    public void addB(double value) {
        if (type == Type.PAIRED) {
            throw new IllegalStateException("paired t-test requires addPair");
        }
        b.add(value);
    }

    public void merge(TTest other) {
        if (other.type != type || other.tails != tails) {
            throw new IllegalArgumentException("cannot merge t-test accumulators of different type or tails");
        }
        if (other == this) {
            TTestStats ca = other.a.copy();
            TTestStats cb = other.b.copy();
            a.merge(ca);
            b.merge(cb);
            return;
        }
        a.merge(other.a);
        b.merge(other.b);
    }

    public Result result() {
        return switch (type) {
            case PAIRED -> paired(a, tails);
            case HOMOSCEDASTIC -> homoscedastic(a, b, tails);
            case HETEROSCEDASTIC -> heteroscedastic(a, b, tails);
        };
    }

    public double pValue() {
        return result().pValue();
    }

    public static Result paired(TTestStats differences, int tails) {
        checkTails(tails);
        long n = differences.count();
        if (n < 2) {
            return new Result(Double.NaN, Double.NaN, Double.NaN);
        }
        double sd = Math.sqrt(differences.variance() / n);
        double t = differences.mean() / sd;
        double df = n - 1;
        return new Result(t, df, pValue(t, df, tails));
    }

    public static Result homoscedastic(TTestStats a, TTestStats b, int tails) {
        checkTails(tails);
        if (a.count() < 2 || b.count() < 2) {
            return new Result(Double.NaN, Double.NaN, Double.NaN);
        }
        double df = a.count() + b.count() - 2;
        double pooled = ((a.count() - 1) * a.variance() + (b.count() - 1) * b.variance()) / df;
        double se = Math.sqrt(pooled * (1.0 / a.count() + 1.0 / b.count()));
        double t = (a.mean() - b.mean()) / se;
        return new Result(t, df, pValue(t, df, tails));
    }

    public static Result heteroscedastic(TTestStats a, TTestStats b, int tails) {
        checkTails(tails);
        if (a.count() < 2 || b.count() < 2) {
            return new Result(Double.NaN, Double.NaN, Double.NaN);
        }
        double s2an = a.variance() / a.count();
        double s2bn = b.variance() / b.count();
        double variance = s2an + s2bn;
        double df = variance * variance / (s2an * s2an / (a.count() - 1) + s2bn * s2bn / (b.count() - 1));
        double t = (a.mean() - b.mean()) / Math.sqrt(variance);
        return new Result(t, df, pValue(t, df, tails));
    }

    public static double pValue(double t, double degreesOfFreedom, int tails) {
        checkTails(tails);
        if (Double.isNaN(t) || Double.isNaN(degreesOfFreedom) || !(degreesOfFreedom > 0)) {
            return Double.NaN;
        }
        return TDistribution.cdf(-Math.abs(t), degreesOfFreedom) * tails;
    }

    public void writeTo(DataOutput out) throws IOException {
        out.writeByte(SERIAL_VERSION);
        out.writeByte(type.ordinal());
        out.writeByte(tails);
        a.writeTo(out);
        b.writeTo(out);
    }

    public static TTest readFrom(DataInput in) throws IOException {
        int version = in.readUnsignedByte();
        if (version != SERIAL_VERSION) {
            throw new IOException("unsupported t-test serialization version: " + version);
        }
        int ordinal = in.readUnsignedByte();
        if (ordinal >= Type.values().length) {
            throw new IOException("invalid t-test type: " + ordinal);
        }
        int tails = in.readUnsignedByte();
        if (tails != 1 && tails != 2) {
            throw new IOException("invalid tails: " + tails);
        }
        TTest t = new TTest(Type.values()[ordinal], tails);
        t.a.merge(TTestStats.readFrom(in));
        t.b.merge(TTestStats.readFrom(in));
        return t;
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            writeTo(out);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static TTest fromBytes(byte[] bytes) {
        try {
            return readFrom(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
