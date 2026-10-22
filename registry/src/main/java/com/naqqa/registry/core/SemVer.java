package com.naqqa.registry.core;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SemVer(BigInteger major, BigInteger minor, BigInteger patch, String[] preRelease) implements Comparable<SemVer> {

    static final Pattern FORMAT = Pattern.compile(
            "^(0|[1-9]\\d{0,17})\\.(0|[1-9]\\d{0,17})\\.(0|[1-9]\\d{0,17})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    static final int MAX_LENGTH = 128;

    public static Optional<SemVer> parse(String value) {
        if (value == null || value.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(value);
        if (!m.matches()) {
            return Optional.empty();
        }
        String[] pre = m.group(4) == null ? new String[0] : m.group(4).split("\\.");
        return Optional.of(new SemVer(new BigInteger(m.group(1)), new BigInteger(m.group(2)), new BigInteger(m.group(3)), pre));
    }

    public static boolean isValid(String value) {
        return parse(value).isPresent();
    }

    public boolean isPreRelease() {
        return preRelease.length > 0;
    }

    @Override
    public int compareTo(SemVer other) {
        int c = major.compareTo(other.major);
        if (c == 0) {
            c = minor.compareTo(other.minor);
        }
        if (c == 0) {
            c = patch.compareTo(other.patch);
        }
        if (c != 0) {
            return c;
        }
        if (preRelease.length == 0 && other.preRelease.length == 0) {
            return 0;
        }
        if (preRelease.length == 0) {
            return 1;
        }
        if (other.preRelease.length == 0) {
            return -1;
        }
        int n = Math.min(preRelease.length, other.preRelease.length);
        for (int i = 0; i < n; i++) {
            int part = comparePart(preRelease[i], other.preRelease[i]);
            if (part != 0) {
                return part;
            }
        }
        return Integer.compare(preRelease.length, other.preRelease.length);
    }

    private static int comparePart(String a, String b) {
        boolean an = a.chars().allMatch(Character::isDigit);
        boolean bn = b.chars().allMatch(Character::isDigit);
        if (an && bn) {
            return new BigInteger(a).compareTo(new BigInteger(b));
        }
        if (an) {
            return -1;
        }
        if (bn) {
            return 1;
        }
        return a.compareTo(b);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SemVer other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return major.hashCode() * 31 * 31 + minor.hashCode() * 31 + patch.hashCode() + Arrays.hashCode(preRelease);
    }
}
