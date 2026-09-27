package com.naqqa.elasticsearch.script.painless;

import com.naqqa.elasticsearch.script.DocLookup;
import com.naqqa.elasticsearch.script.GeoPoint;
import com.naqqa.elasticsearch.script.ScriptDocValues;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Sandbox {

    private static final Set<Class<?>> ALLOWED_CLASSES = new HashSet<>();
    private static final Map<String, Class<?>> ALLOWED_TYPE_NAMES = new HashMap<>();

    static {
        Class<?>[] classes = {
            Object.class, String.class, StringBuilder.class, CharSequence.class, Comparable.class,
            Number.class, Integer.class, Long.class, Float.class, Double.class, Short.class, Byte.class, Character.class, Boolean.class,
            Math.class, Objects.class,
            Iterable.class, Iterator.class, Collection.class, AbstractCollection.class,
            List.class, AbstractList.class, ArrayList.class, LinkedList.class,
            Map.class, AbstractMap.class, HashMap.class, LinkedHashMap.class, TreeMap.class, SortedMap.class, Map.Entry.class,
            Set.class, HashSet.class, LinkedHashSet.class, TreeSet.class, SortedSet.class,
            Collections.class, Comparator.class,
            Optional.class, OptionalInt.class, OptionalLong.class, OptionalDouble.class,
            Pattern.class, Matcher.class, Base64.class, Base64.Encoder.class, Base64.Decoder.class,
            Instant.class, ZonedDateTime.class, LocalDate.class, LocalDateTime.class, LocalTime.class,
            Duration.class, Period.class, ZoneId.class, ZoneOffset.class, DayOfWeek.class, Month.class,
            Year.class, YearMonth.class, MonthDay.class,
            DocLookup.class, GeoPoint.class, ScriptDocValues.class,
            ScriptDocValues.Longs.class, ScriptDocValues.Doubles.class, ScriptDocValues.Strings.class,
            ScriptDocValues.Booleans.class, ScriptDocValues.Dates.class, ScriptDocValues.GeoPoints.class,
            ScriptDocValues.DenseVector.class,
            RuntimeException.class, Exception.class, ArithmeticException.class, NullPointerException.class,
            IllegalArgumentException.class, IllegalStateException.class, IndexOutOfBoundsException.class,
            ClassCastException.class, NumberFormatException.class,
            int.class, long.class, float.class, double.class, boolean.class, byte.class, short.class, char.class, void.class,
            int[].class, long[].class, float[].class, double[].class, boolean[].class, byte[].class, short[].class, char[].class,
            String[].class, Object[].class
        };
        Collections.addAll(ALLOWED_CLASSES, classes);
        for (Class<?> c : classes) {
            ALLOWED_TYPE_NAMES.putIfAbsent(c.getSimpleName(), c);
        }
        ALLOWED_TYPE_NAMES.put("Exception", RuntimeException.class);
        ALLOWED_TYPE_NAMES.put("ArrayList", ArrayList.class);
        ALLOWED_TYPE_NAMES.put("HashMap", HashMap.class);
        ALLOWED_TYPE_NAMES.put("List", List.class);
        ALLOWED_TYPE_NAMES.put("Map", Map.class);
        ALLOWED_TYPE_NAMES.put("Set", Set.class);
    }

    private Sandbox() {
    }

    public static boolean isClassAllowed(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (ALLOWED_CLASSES.contains(type)) {
            return true;
        }
        for (Class<?> allowed : ALLOWED_CLASSES) {
            if (allowed.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    public static void checkClass(Class<?> type) {
        if (!isClassAllowed(type)) {
            throw new PainlessSecurityException("[" + type.getName() + "] is not whitelisted in a script context");
        }
    }

    public static boolean isTypeNameKnown(String simpleTypeName) {
        return ALLOWED_TYPE_NAMES.containsKey(simpleTypeName);
    }

    public static Class<?> resolveTypeName(String simpleTypeName) {
        Class<?> c = ALLOWED_TYPE_NAMES.get(simpleTypeName);
        if (c == null) {
            throw new PainlessSecurityException("[" + simpleTypeName + "] is not whitelisted in a script context");
        }
        return c;
    }

    public static void registerAllowedClass(Class<?> type) {
        ALLOWED_CLASSES.add(type);
        ALLOWED_TYPE_NAMES.putIfAbsent(type.getSimpleName(), type);
    }
}
