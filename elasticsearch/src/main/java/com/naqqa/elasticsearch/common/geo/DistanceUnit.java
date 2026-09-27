package com.naqqa.elasticsearch.common.geo;

import java.util.Locale;
import java.util.Objects;

public enum DistanceUnit {
    INCH(0.0254, "in", "inch"),
    YARD(0.9144, "yd", "yards"),
    FEET(0.3048, "ft", "feet"),
    KILOMETERS(1000.0, "km", "kilometers"),
    NAUTICALMILES(1852.0, "NM", "nmi", "nauticalmiles"),
    MILLIMETERS(0.001, "mm", "millimeters"),
    CENTIMETERS(0.01, "cm", "centimeters"),
    MILES(1609.344, "mi", "miles"),
    METERS(1.0, "m", "meters");

    public static final DistanceUnit DEFAULT = METERS;

    private final double meters;
    private final String[] names;

    DistanceUnit(double meters, String... names) {
        this.meters = meters;
        this.names = names;
    }

    public double getMeters() {
        return meters;
    }

    public String[] getNames() {
        return names.clone();
    }

    public String getName() {
        return names[0];
    }

    public double getEarthCircumference() {
        return GeoUtils.EARTH_EQUATOR / meters;
    }

    public double getEarthRadius() {
        return GeoUtils.EARTH_SEMI_MAJOR_AXIS / meters;
    }

    public double getDistancePerDegree() {
        return GeoUtils.EARTH_EQUATOR / (360.0 * meters);
    }

    public double toMeters(double distance) {
        return distance * meters;
    }

    public double fromMeters(double distance) {
        return distance / meters;
    }

    public double convert(double distance, DistanceUnit unit) {
        return convert(distance, this, unit);
    }

    public static double convert(double distance, DistanceUnit from, DistanceUnit to) {
        if (from == to) {
            return distance;
        }
        return distance * from.meters / to.meters;
    }

    public String toString(double distance) {
        return distance + names[0];
    }

    @Override
    public String toString() {
        return names[0];
    }

    public static DistanceUnit fromString(String unit) {
        Objects.requireNonNull(unit, "unit must not be null");
        for (DistanceUnit u : values()) {
            for (String name : u.names) {
                if (name.equals(unit)) {
                    return u;
                }
            }
        }
        String lower = unit.toLowerCase(Locale.ROOT);
        for (DistanceUnit u : values()) {
            for (String name : u.names) {
                if (name.toLowerCase(Locale.ROOT).equals(lower)) {
                    return u;
                }
            }
        }
        throw new IllegalArgumentException("No distance unit match [" + unit + "]");
    }

    public static DistanceUnit parseUnit(String distance, DistanceUnit defaultUnit) {
        String trimmed = distance.trim();
        DistanceUnit best = null;
        int bestLen = 0;
        for (DistanceUnit u : values()) {
            for (String name : u.names) {
                if (name.length() > bestLen && trimmed.endsWith(name)) {
                    best = u;
                    bestLen = name.length();
                }
            }
        }
        return best == null ? defaultUnit : best;
    }

    public static double parse(String distance, DistanceUnit defaultUnit, DistanceUnit to) {
        Distance d = Distance.parseDistance(distance, defaultUnit);
        return convert(d.value(), d.unit(), to);
    }

    public double parse(String distance, DistanceUnit defaultUnit) {
        return parse(distance, defaultUnit, this);
    }

    public static double parseToMeters(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            return parse(s, DEFAULT, METERS);
        }
        throw new IllegalArgumentException("cannot parse distance from [" + value + "]");
    }

    public record Distance(double value, DistanceUnit unit) implements Comparable<Distance> {

        public Distance {
            Objects.requireNonNull(unit, "unit must not be null");
        }

        public Distance convert(DistanceUnit target) {
            return new Distance(DistanceUnit.convert(value, unit, target), target);
        }

        public double toMeters() {
            return unit.toMeters(value);
        }

        public static Distance parseDistance(String distance) {
            return parseDistance(distance, DEFAULT);
        }

        public static Distance parseDistance(String distance, DistanceUnit defaultUnit) {
            if (distance == null) {
                throw new IllegalArgumentException("distance must not be null");
            }
            String trimmed = distance.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            DistanceUnit best = null;
            int bestLen = 0;
            for (DistanceUnit u : DistanceUnit.values()) {
                for (String name : u.names) {
                    if (name.length() > bestLen && (trimmed.endsWith(name) || lower.endsWith(name.toLowerCase(Locale.ROOT)))) {
                        String rest = trimmed.substring(0, trimmed.length() - name.length()).trim();
                        if (isNumber(rest)) {
                            best = u;
                            bestLen = name.length();
                        }
                    }
                }
            }
            String number = best == null ? trimmed : trimmed.substring(0, trimmed.length() - bestLen).trim();
            try {
                return new Distance(Double.parseDouble(number), best == null ? defaultUnit : best);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("failed to parse distance [" + distance + "]", e);
            }
        }

        private static boolean isNumber(String s) {
            if (s.isEmpty()) {
                return false;
            }
            try {
                Double.parseDouble(s);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        public int compareTo(Distance o) {
            return Double.compare(toMeters(), o.toMeters());
        }

        @Override
        public String toString() {
            return unit.toString(value);
        }
    }
}
