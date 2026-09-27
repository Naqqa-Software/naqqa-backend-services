package com.naqqa.elasticsearch.common.unit;

public final class Fuzziness {

    public static final Fuzziness AUTO = new Fuzziness("AUTO", -1, 3, 6);
    public static final Fuzziness ZERO = new Fuzziness("0", 0, -1, -1);
    public static final Fuzziness ONE = new Fuzziness("1", 1, -1, -1);
    public static final Fuzziness TWO = new Fuzziness("2", 2, -1, -1);

    private final String asString;
    private final float fixedValue;
    private final int lowDistance;
    private final int highDistance;
    private final boolean isAuto;

    private Fuzziness(String asString, float fixedValue, int lowDistance, int highDistance) {
        this.asString = asString;
        this.fixedValue = fixedValue;
        this.lowDistance = lowDistance;
        this.highDistance = highDistance;
        this.isAuto = lowDistance >= 0 || asString.startsWith("AUTO");
    }

    public static Fuzziness fromEdits(int edits) {
        if (edits == 0) {
            return ZERO;
        } else if (edits == 1) {
            return ONE;
        } else if (edits == 2) {
            return TWO;
        }
        throw new IllegalArgumentException("Fuzziness edit distance must be 0, 1 or 2, got " + edits);
    }

    public static Fuzziness fromString(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.equalsIgnoreCase("AUTO")) {
            return AUTO;
        }
        if (v.toUpperCase(java.util.Locale.ROOT).startsWith("AUTO:")) {
            String rest = v.substring(5);
            String[] parts = rest.split(",");
            if (parts.length != 2) {
                throw new IllegalArgumentException("AUTO:<low>,<high> was expected but got [" + value + "]");
            }
            int low = Integer.parseInt(parts[0].trim());
            int high = Integer.parseInt(parts[1].trim());
            if (low < 0 || high < 0 || low > high) {
                throw new IllegalArgumentException("Invalid AUTO fuzziness bounds [" + value + "]");
            }
            return new Fuzziness("AUTO:" + low + "," + high, -1, low, high);
        }
        try {
            float f = Float.parseFloat(v);
            if (f == 0) {
                return ZERO;
            } else if (f == 1) {
                return ONE;
            } else if (f == 2) {
                return TWO;
            }
            return new Fuzziness(v, f, -1, -1);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid fuzziness value [" + value + "]", e);
        }
    }

    public boolean isAutomatic() {
        return isAuto;
    }

    public int asDistance(String text) {
        if (isAuto) {
            int low = lowDistance < 0 ? 3 : lowDistance;
            int high = highDistance < 0 ? 6 : highDistance;
            int len = text == null ? 0 : text.codePointCount(0, text.length());
            if (len < low) {
                return 0;
            } else if (len < high) {
                return 1;
            } else {
                return 2;
            }
        }
        return Math.min(2, (int) fixedValue);
    }

    public float asFloat() {
        return isAuto ? -1 : fixedValue;
    }

    public String asString() {
        return asString;
    }

    @Override
    public String toString() {
        return asString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Fuzziness other)) {
            return false;
        }
        return asString.equals(other.asString);
    }

    @Override
    public int hashCode() {
        return asString.hashCode();
    }
}
