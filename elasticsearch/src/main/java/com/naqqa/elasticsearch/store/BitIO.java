package com.naqqa.elasticsearch.store;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

public final class BitIO {

    private static final VarHandle SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_BE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private BitIO() {
    }

    public static short getShort(byte[] b, int offset) {
        return (short) SHORT.get(b, offset);
    }

    public static int getInt(byte[] b, int offset) {
        return (int) INT.get(b, offset);
    }

    public static long getLong(byte[] b, int offset) {
        return (long) LONG.get(b, offset);
    }

    public static void putShort(byte[] b, int offset, short v) {
        SHORT.set(b, offset, v);
    }

    public static void putInt(byte[] b, int offset, int v) {
        INT.set(b, offset, v);
    }

    public static void putLong(byte[] b, int offset, long v) {
        LONG.set(b, offset, v);
    }

    public static int getIntBE(byte[] b, int offset) {
        return (int) INT_BE.get(b, offset);
    }

    public static long getLongBE(byte[] b, int offset) {
        return (long) LONG_BE.get(b, offset);
    }

    public static void putIntBE(byte[] b, int offset, int v) {
        INT_BE.set(b, offset, v);
    }

    public static void putLongBE(byte[] b, int offset, long v) {
        LONG_BE.set(b, offset, v);
    }
}
