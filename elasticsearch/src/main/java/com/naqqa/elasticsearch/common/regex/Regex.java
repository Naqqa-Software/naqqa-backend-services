package com.naqqa.elasticsearch.common.regex;

public final class Regex {

    private Regex() {
    }

    public static boolean isSimpleMatchPattern(String str) {
        return str.indexOf('*') != -1 || str.indexOf('?') != -1;
    }

    public static boolean simpleMatch(String pattern, String str) {
        if (pattern == null || str == null) {
            return false;
        }
        return simpleMatch(pattern, str, false);
    }

    public static boolean simpleMatch(String pattern, String str, boolean caseInsensitive) {
        if (pattern == null || str == null) {
            return false;
        }
        if (caseInsensitive) {
            pattern = pattern.toLowerCase(java.util.Locale.ROOT);
            str = str.toLowerCase(java.util.Locale.ROOT);
        }
        return simpleMatchWithNormalizedStrings(pattern, str);
    }

    public static boolean simpleMatch(String[] patterns, String str) {
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (simpleMatch(pattern, str)) {
                return true;
            }
        }
        return false;
    }

    private static boolean simpleMatchWithNormalizedStrings(String pattern, String str) {
        int patternIdx = 0;
        int strIdx = 0;
        int patternLen = pattern.length();
        int strLen = str.length();
        int starIdx = -1;
        int strIdxAtStar = -1;
        while (strIdx < strLen) {
            if (patternIdx < patternLen) {
                char c = pattern.charAt(patternIdx);
                if (c == '*') {
                    starIdx = patternIdx;
                    strIdxAtStar = strIdx;
                    patternIdx++;
                    continue;
                }
                if (c == '?' || c == str.charAt(strIdx)) {
                    patternIdx++;
                    strIdx++;
                    continue;
                }
            }
            if (starIdx == -1) {
                return false;
            }
            patternIdx = starIdx + 1;
            strIdxAtStar++;
            strIdx = strIdxAtStar;
        }
        while (patternIdx < patternLen && pattern.charAt(patternIdx) == '*') {
            patternIdx++;
        }
        return patternIdx == patternLen;
    }
}
