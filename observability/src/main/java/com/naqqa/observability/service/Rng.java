package com.naqqa.observability.service;

public final class Rng {

    private static final long MASK = 0xFFFFFFFFL;
    private static final double DIV = 4294967295.0;

    private long state;

    public Rng(long seed) {
        this.state = seed & MASK;
    }

    public double next() {
        state = (state * 1664525L + 1013904223L) & MASK;
        return state / DIV;
    }

    public static long seedFor(String value) {
        long h = 2166136261L;
        for (int i = 0; i < value.length(); i++) {
            h = (h ^ value.charAt(i)) & MASK;
            h = (h * 16777619L) & MASK;
        }
        return h & MASK;
    }

    public static long strHash(String value) {
        return seedFor(value);
    }

    public String hex(int length) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) {
            sb.append(Integer.toHexString((int) Math.floor(next() * 16)));
        }
        return sb.substring(0, length);
    }
}
