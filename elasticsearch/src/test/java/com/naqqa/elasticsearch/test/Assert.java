package com.naqqa.elasticsearch.test;

import java.util.Arrays;
import java.util.Objects;

public final class Assert {

    private Assert() {
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    public static void assertTrue(boolean condition) {
        assertTrue(condition, "expected true");
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertFalse(boolean condition) {
        assertTrue(!condition, "expected false");
    }

    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    public static void assertNull(Object value) {
        if (value != null) {
            throw new AssertionError("expected null but was <" + value + ">");
        }
    }

    public static void assertNotNull(Object value) {
        if (value == null) {
            throw new AssertionError("expected non-null");
        }
    }

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.deepEquals(expected, actual)) {
            throw new AssertionError((message == null ? "" : message + ": ") + "expected <" + render(expected) + "> but was <" + render(actual) + ">");
        }
    }

    public static void assertEquals(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void assertEquals(double expected, double actual, double delta) {
        if (Double.compare(expected, actual) != 0 && Math.abs(expected - actual) > delta) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + "> (delta " + delta + ")");
        }
    }

    public static void assertNotEquals(Object unexpected, Object actual) {
        if (Objects.deepEquals(unexpected, actual)) {
            throw new AssertionError("expected value different from <" + render(actual) + ">");
        }
    }

    public static void assertSame(Object expected, Object actual) {
        if (expected != actual) {
            throw new AssertionError("expected same instance <" + expected + "> but was <" + actual + ">");
        }
    }

    public static <T extends Throwable> T assertThrows(Class<T> type, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError("expected " + type.getName() + " but got " + t.getClass().getName() + ": " + t.getMessage(), t);
        }
        throw new AssertionError("expected " + type.getName() + " to be thrown");
    }

    private static String render(Object value) {
        if (value instanceof Object[] array) {
            return Arrays.deepToString(array);
        }
        if (value instanceof byte[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof int[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof long[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof float[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof double[] array) {
            return Arrays.toString(array);
        }
        return String.valueOf(value);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
