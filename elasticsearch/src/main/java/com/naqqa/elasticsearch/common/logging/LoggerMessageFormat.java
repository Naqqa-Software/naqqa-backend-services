package com.naqqa.elasticsearch.common.logging;

import java.util.Arrays;

public final class LoggerMessageFormat {

    private LoggerMessageFormat() {
    }

    public static String format(String messagePattern, Object... args) {
        return format(null, messagePattern, args);
    }

    public static String format(String prefix, String messagePattern, Object... args) {
        if (messagePattern == null) {
            return prefix;
        }
        if (args == null || args.length == 0) {
            return prefix == null ? messagePattern : prefix + messagePattern;
        }
        StringBuilder sb = new StringBuilder(messagePattern.length() + 50);
        if (prefix != null) {
            sb.append(prefix);
        }
        int argIndex = 0;
        int i = 0;
        int len = messagePattern.length();
        while (i < len) {
            char c = messagePattern.charAt(i);
            if (c == '\\' && i + 2 < len && messagePattern.charAt(i + 1) == '{' && messagePattern.charAt(i + 2) == '}') {
                sb.append("{}");
                i += 3;
                continue;
            }
            if (c == '{' && i + 1 < len && messagePattern.charAt(i + 1) == '}' && argIndex < args.length) {
                appendArg(sb, args[argIndex++]);
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static void appendArg(StringBuilder sb, Object arg) {
        if (arg == null) {
            sb.append("null");
        } else if (arg instanceof Object[] a) {
            sb.append(Arrays.deepToString(a));
        } else if (arg instanceof int[] a) {
            sb.append(Arrays.toString(a));
        } else if (arg instanceof long[] a) {
            sb.append(Arrays.toString(a));
        } else if (arg instanceof byte[] a) {
            sb.append(Arrays.toString(a));
        } else if (arg instanceof double[] a) {
            sb.append(Arrays.toString(a));
        } else if (arg instanceof float[] a) {
            sb.append(Arrays.toString(a));
        } else if (arg instanceof short[] a) {
            sb.append(Arrays.toString(a));
        } else if (arg instanceof char[] a) {
            sb.append(Arrays.toString(a));
        } else if (arg instanceof boolean[] a) {
            sb.append(Arrays.toString(a));
        } else {
            try {
                sb.append(arg);
            } catch (RuntimeException e) {
                sb.append("[FAILED toString()]");
            }
        }
    }
}
