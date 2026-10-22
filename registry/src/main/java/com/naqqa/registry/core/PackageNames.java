package com.naqqa.registry.core;

import java.util.regex.Pattern;

public final class PackageNames {

    static final int MAX_LENGTH = 214;
    static final Pattern SEGMENT = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");

    private PackageNames() {
    }

    public static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }
        if (name.startsWith("@")) {
            int slash = name.indexOf('/');
            if (slash < 2 || slash != name.lastIndexOf('/')) {
                return false;
            }
            return isSegment(name.substring(1, slash)) && isSegment(name.substring(slash + 1));
        }
        return name.indexOf('/') < 0 && isSegment(name);
    }

    public static boolean isSegment(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_LENGTH && SEGMENT.matcher(value).matches()
                && !value.equals(".") && !value.equals("..");
    }

    public static boolean isValidPattern(String pattern) {
        if (pattern == null) {
            return false;
        }
        if (pattern.startsWith("@") && pattern.endsWith("/*")) {
            return isSegment(pattern.substring(1, pattern.length() - 2));
        }
        return isValid(pattern);
    }

    public static boolean matches(String pattern, String name) {
        if (pattern == null || name == null) {
            return false;
        }
        if (pattern.endsWith("/*")) {
            return name.startsWith(pattern.substring(0, pattern.length() - 1)) && isValid(name);
        }
        return pattern.equals(name);
    }

    public static String baseName(String name) {
        int slash = name.indexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    public static String tarballFileName(String name, String version) {
        return baseName(name) + "-" + version + ".tgz";
    }
}
