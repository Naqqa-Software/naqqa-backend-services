package com.naqqa.elasticsearch.common.unit;

public final class RatioValue {

    private final double percent;

    private RatioValue(double percent) {
        this.percent = percent;
    }

    public static RatioValue parseRatioValue(String sValue) {
        if (sValue.endsWith("%")) {
            String percentAsString = sValue.substring(0, sValue.length() - 1);
            try {
                double percent = Double.parseDouble(percentAsString);
                if (percent < 0 || percent > 100) {
                    throw new IllegalArgumentException(
                        "Percentage should be in [0-100], got [" + percentAsString + "]"
                    );
                }
                return new RatioValue(percent);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Failed to parse [" + sValue + "] as a percentage", e);
            }
        } else {
            try {
                double ratio = Double.parseDouble(sValue);
                if (ratio < 0 || ratio > 1.0) {
                    throw new IllegalArgumentException("Ratio should be in [0-1.0], got [" + ratio + "]");
                }
                return new RatioValue(100.0 * ratio);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Failed to parse [" + sValue + "] as a ratio", e);
            }
        }
    }

    public double getAsPercent() {
        return percent;
    }

    public double getAsRatio() {
        return percent / 100.0;
    }

    public String formatNoTrailingZerosPercent() {
        String p = String.valueOf(percent);
        if (p.endsWith(".0")) {
            p = p.substring(0, p.length() - 2);
        }
        return p + "%";
    }

    @Override
    public String toString() {
        return formatNoTrailingZerosPercent();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RatioValue other)) {
            return false;
        }
        return Double.compare(percent, other.percent) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(percent);
    }
}
