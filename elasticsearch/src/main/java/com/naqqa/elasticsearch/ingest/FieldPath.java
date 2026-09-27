package com.naqqa.elasticsearch.ingest;

import java.util.List;
import java.util.Map;

public final class FieldPath {

    private FieldPath() {
    }

    public static String[] segments(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path cannot be null or empty");
        }
        return path.split("\\.");
    }

    private static Integer tryIndex(String segment) {
        if (segment.isEmpty()) {
            return null;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (!Character.isDigit(c)) {
                if (!(i == 0 && c == '-')) {
                    return null;
                }
            }
        }
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean hasValue(Object root, String[] segs) {
        Object current = root;
        for (String seg : segs) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(seg)) {
                    return false;
                }
                current = map.get(seg);
            } else if (current instanceof List<?> list) {
                Integer idx = tryIndex(seg);
                if (idx == null) {
                    return false;
                }
                int i = idx < 0 ? list.size() + idx : idx;
                if (i < 0 || i >= list.size()) {
                    return false;
                }
                current = list.get(i);
            } else {
                return false;
            }
        }
        return true;
    }

    public static Object getValue(Object root, String[] segs) {
        Object current = root;
        for (String seg : segs) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(seg)) {
                    throw new IllegalArgumentException("field [" + seg + "] not present as part of path [" + String.join(".", segs) + "]");
                }
                current = map.get(seg);
            } else if (current instanceof List<?> list) {
                Integer idx = tryIndex(seg);
                if (idx == null) {
                    throw new IllegalArgumentException("[" + seg + "] is not a valid list index");
                }
                int i = idx < 0 ? list.size() + idx : idx;
                if (i < 0 || i >= list.size()) {
                    throw new IllegalArgumentException("[" + idx + "] is out of bounds for array with length [" + list.size() + "]");
                }
                current = list.get(i);
            } else {
                throw new IllegalArgumentException("cannot resolve [" + seg + "] since parent is not a map or list");
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    public static void setValue(Object root, String[] segs, Object value) {
        Object current = root;
        for (int i = 0; i < segs.length - 1; i++) {
            String seg = segs[i];
            if (current instanceof Map<?, ?> mapRaw) {
                Map<String, Object> map = (Map<String, Object>) mapRaw;
                Object next = map.get(seg);
                if (next == null && !map.containsKey(seg)) {
                    next = new java.util.LinkedHashMap<String, Object>();
                    map.put(seg, next);
                }
                if (!(next instanceof Map) && !(next instanceof List)) {
                    throw new IllegalArgumentException("cannot set value for path [" + String.join(".", segs) + "], [" + seg + "] is not a container");
                }
                current = next;
            } else if (current instanceof List<?> list) {
                Integer idx = tryIndex(seg);
                if (idx == null) {
                    throw new IllegalArgumentException("[" + seg + "] is not a valid list index");
                }
                int ix = idx < 0 ? list.size() + idx : idx;
                if (ix < 0 || ix >= list.size()) {
                    throw new IllegalArgumentException("[" + idx + "] is out of bounds for array with length [" + list.size() + "]");
                }
                current = list.get(ix);
            } else {
                throw new IllegalArgumentException("cannot set value for path [" + String.join(".", segs) + "]");
            }
        }
        String last = segs[segs.length - 1];
        if (current instanceof Map<?, ?> mapRaw) {
            ((Map<String, Object>) mapRaw).put(last, value);
        } else if (current instanceof List<?> listRaw) {
            List<Object> list = (List<Object>) listRaw;
            Integer idx = tryIndex(last);
            if (idx == null) {
                throw new IllegalArgumentException("[" + last + "] is not a valid list index");
            }
            if (idx == list.size()) {
                list.add(value);
            } else {
                int ix = idx < 0 ? list.size() + idx : idx;
                if (ix < 0 || ix >= list.size()) {
                    throw new IllegalArgumentException("[" + idx + "] is out of bounds for array with length [" + list.size() + "]");
                }
                list.set(ix, value);
            }
        } else {
            throw new IllegalArgumentException("cannot set value for path [" + String.join(".", segs) + "]");
        }
    }

    @SuppressWarnings("unchecked")
    public static void removeValue(Object root, String[] segs) {
        Object current = root;
        for (int i = 0; i < segs.length - 1; i++) {
            current = getValue(current, new String[] {segs[i]});
        }
        String last = segs[segs.length - 1];
        if (current instanceof Map<?, ?> mapRaw) {
            ((Map<String, Object>) mapRaw).remove(last);
        } else if (current instanceof List<?> listRaw) {
            List<Object> list = (List<Object>) listRaw;
            Integer idx = tryIndex(last);
            if (idx != null) {
                int ix = idx < 0 ? list.size() + idx : idx;
                if (ix >= 0 && ix < list.size()) {
                    list.remove(ix);
                }
            }
        }
    }
}
