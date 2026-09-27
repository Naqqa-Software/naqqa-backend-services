package com.naqqa.elasticsearch.common.unit;

public enum DistanceUnit {

    MILLIMETERS(0.001, "mm"),
    CENTIMETERS(0.01, "cm"),
    METERS(1.0, "m"),
    KILOMETERS(1000.0, "km"),
    FEET(0.3048, "ft"),
    YARD(0.9144, "yd"),
    INCH(0.0254, "in"),
    MILES(1609.344, "mi"),
    NAUTICALMILES(1852.0, "NM", "nmi");

    private final double metersPerUnit;
    private final String[] names;

    DistanceUnit(double metersPerUnit, String... names) {
        this.metersPerUnit = metersPerUnit;
        this.names = names;
    }

    public double toMeters(double distance) {
        return distance * metersPerUnit;
    }

    public double fromMeters(double distanceInMeters) {
        return distanceInMeters / metersPerUnit;
    }

    public double convert(double distance, DistanceUnit target) {
        return target.fromMeters(toMeters(distance));
    }

    public String getSuffix() {
        return names[0];
    }

    public static DistanceUnit fromString(String unit) {
        for (DistanceUnit u : values()) {
            for (String n : u.names) {
                if (n.equalsIgnoreCase(unit)) {
                    return u;
                }
            }
        }
        throw new IllegalArgumentException("Unknown distance unit [" + unit + "]");
    }

    public static double parseDistance(String distance) {
        return parse(distance).getKey();
    }

    public static DistanceUnit parseUnit(String distance) {
        return parse(distance).getValue();
    }

    private static java.util.Map.Entry<Double, DistanceUnit> parse(String distance) {
        String trimmed = distance.trim();
        int idx = trimmed.length();
        while (idx > 0 && !Character.isDigit(trimmed.charAt(idx - 1)) && trimmed.charAt(idx - 1) != '.') {
            idx--;
        }
        String numberPart = trimmed.substring(0, idx);
        String unitPart = trimmed.substring(idx).trim();
        double value = Double.parseDouble(numberPart);
        DistanceUnit unit = unitPart.isEmpty() ? METERS : fromString(unitPart);
        return java.util.Map.entry(value, unit);
    }

    public static double parse(String distance, DistanceUnit defaultUnit, DistanceUnit convertTo) {
        String trimmed = distance.trim();
        int idx = trimmed.length();
        while (idx > 0 && !Character.isDigit(trimmed.charAt(idx - 1)) && trimmed.charAt(idx - 1) != '.') {
            idx--;
        }
        String numberPart = trimmed.substring(0, idx);
        String unitPart = trimmed.substring(idx).trim();
        double value = Double.parseDouble(numberPart);
        DistanceUnit unit = unitPart.isEmpty() ? defaultUnit : fromString(unitPart);
        return unit.convert(value, convertTo);
    }
}
