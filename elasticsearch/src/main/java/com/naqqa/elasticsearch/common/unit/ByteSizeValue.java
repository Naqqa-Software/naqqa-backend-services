package com.naqqa.elasticsearch.common.unit;

public final class ByteSizeValue implements Comparable<ByteSizeValue> {

    private final long size;
    private final ByteSizeUnit unit;

    public ByteSizeValue(long size, ByteSizeUnit unit) {
        if (size < -1) {
            throw new IllegalArgumentException("size must be >= -1, got " + size);
        }
        this.size = size;
        this.unit = unit;
    }

    public static ByteSizeValue ofBytes(long bytes) {
        return new ByteSizeValue(bytes, ByteSizeUnit.BYTES);
    }

    public static ByteSizeValue ofKb(long kb) {
        return new ByteSizeValue(kb, ByteSizeUnit.KB);
    }

    public static ByteSizeValue ofMb(long mb) {
        return new ByteSizeValue(mb, ByteSizeUnit.MB);
    }

    public static ByteSizeValue ofGb(long gb) {
        return new ByteSizeValue(gb, ByteSizeUnit.GB);
    }

    public long getBytes() {
        return unit.toBytes(size);
    }

    public long getKb() {
        return getBytes() / ByteSizeUnit.KB.toBytes(1);
    }

    public long getMb() {
        return getBytes() / ByteSizeUnit.MB.toBytes(1);
    }

    public long getGb() {
        return getBytes() / ByteSizeUnit.GB.toBytes(1);
    }

    public static ByteSizeValue parseBytesSizeValue(String value, String settingName) {
        return parseBytesSizeValue(value, null, settingName);
    }

    public static ByteSizeValue parseBytesSizeValue(String value, ByteSizeValue defaultValue, String settingName) {
        if (value == null) {
            return defaultValue;
        }
        String sValue = value.trim();
        if (sValue.equals("-1")) {
            return new ByteSizeValue(-1, ByteSizeUnit.BYTES);
        }
        if (sValue.isEmpty()) {
            throw new IllegalArgumentException("failed to parse [" + settingName + "]: empty value");
        }
        String lower = sValue.toLowerCase(java.util.Locale.ROOT);
        try {
            if (lower.endsWith("pb")) {
                return new ByteSizeValue(parseNum(sValue, 2), ByteSizeUnit.PB);
            } else if (lower.endsWith("tb")) {
                return new ByteSizeValue(parseNum(sValue, 2), ByteSizeUnit.TB);
            } else if (lower.endsWith("gb")) {
                return new ByteSizeValue(parseNum(sValue, 2), ByteSizeUnit.GB);
            } else if (lower.endsWith("mb")) {
                return new ByteSizeValue(parseNum(sValue, 2), ByteSizeUnit.MB);
            } else if (lower.endsWith("kb")) {
                return new ByteSizeValue(parseNum(sValue, 2), ByteSizeUnit.KB);
            } else if (lower.endsWith("b")) {
                return new ByteSizeValue(parseNum(sValue, 1), ByteSizeUnit.BYTES);
            } else if (lower.equals("0")) {
                return ByteSizeValue.ofBytes(0);
            } else {
                throw new IllegalArgumentException(
                    "failed to parse setting [" + settingName + "] with value [" + sValue + "] as a size in bytes: unit is missing or unrecognized"
                );
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "failed to parse setting [" + settingName + "] with value [" + sValue + "] as a size in bytes: " + e.getMessage(), e
            );
        }
    }

    private static long parseNum(String value, int suffixLength) {
        String numPart = value.substring(0, value.length() - suffixLength).trim();
        if (numPart.contains(".")) {
            return (long) Double.parseDouble(numPart);
        }
        return Long.parseLong(numPart);
    }

    @Override
    public String toString() {
        long bytes = getBytes();
        if (bytes < 0) {
            return "-1b";
        }
        if (bytes >= ByteSizeUnit.PB.toBytes(1) && bytes % ByteSizeUnit.PB.toBytes(1) == 0) {
            return (bytes / ByteSizeUnit.PB.toBytes(1)) + "pb";
        }
        if (bytes >= ByteSizeUnit.TB.toBytes(1) && bytes % ByteSizeUnit.TB.toBytes(1) == 0) {
            return (bytes / ByteSizeUnit.TB.toBytes(1)) + "tb";
        }
        if (bytes >= ByteSizeUnit.GB.toBytes(1) && bytes % ByteSizeUnit.GB.toBytes(1) == 0) {
            return (bytes / ByteSizeUnit.GB.toBytes(1)) + "gb";
        }
        if (bytes >= ByteSizeUnit.MB.toBytes(1) && bytes % ByteSizeUnit.MB.toBytes(1) == 0) {
            return (bytes / ByteSizeUnit.MB.toBytes(1)) + "mb";
        }
        if (bytes >= ByteSizeUnit.KB.toBytes(1) && bytes % ByteSizeUnit.KB.toBytes(1) == 0) {
            return (bytes / ByteSizeUnit.KB.toBytes(1)) + "kb";
        }
        return bytes + "b";
    }

    @Override
    public int compareTo(ByteSizeValue other) {
        return Long.compare(getBytes(), other.getBytes());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ByteSizeValue other)) {
            return false;
        }
        return getBytes() == other.getBytes();
    }

    @Override
    public int hashCode() {
        return Long.hashCode(getBytes());
    }
}
