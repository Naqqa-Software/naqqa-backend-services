package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WordDelimiterSettings {

    private static final Pattern TYPE_PATTERN = Pattern.compile("(.*)\\s*=>\\s*(.*)\\s*$");

    private WordDelimiterSettings() {
    }

    public static int flags(Map<String, Object> params) {
        int flags = 0;
        flags |= flag(WordDelimiterGraphFilter.GENERATE_WORD_PARTS, params, "generate_word_parts", true);
        flags |= flag(WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS, params, "generate_number_parts", true);
        flags |= flag(WordDelimiterGraphFilter.CATENATE_WORDS, params, "catenate_words", false);
        flags |= flag(WordDelimiterGraphFilter.CATENATE_NUMBERS, params, "catenate_numbers", false);
        flags |= flag(WordDelimiterGraphFilter.CATENATE_ALL, params, "catenate_all", false);
        flags |= flag(WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE, params, "split_on_case_change", true);
        flags |= flag(WordDelimiterGraphFilter.PRESERVE_ORIGINAL, params, "preserve_original", false);
        flags |= flag(WordDelimiterGraphFilter.SPLIT_ON_NUMERICS, params, "split_on_numerics", true);
        flags |= flag(WordDelimiterGraphFilter.STEM_ENGLISH_POSSESSIVE, params, "stem_english_possessive", true);
        flags |= flag(WordDelimiterGraphFilter.IGNORE_KEYWORDS, params, "ignore_keywords", false);
        return flags;
    }

    public static boolean adjustOffsets(Map<String, Object> params) {
        return bool(params, "adjust_offsets", true);
    }

    public static byte[] parseTypeTable(List<String> rules) {
        if (rules == null) {
            return WordDelimiterIterator.DEFAULT_WORD_DELIM_TABLE;
        }
        SortedMap<Character, Byte> typeMap = new TreeMap<>();
        for (String rule : rules) {
            Matcher m = TYPE_PATTERN.matcher(rule);
            if (!m.find()) {
                throw new IllegalArgumentException("Invalid Mapping Rule : [" + rule + "]");
            }
            String lhs = parseString(m.group(1).trim());
            Byte rhs = parseType(m.group(2).trim());
            if (lhs.length() != 1) {
                throw new IllegalArgumentException("Invalid Mapping Rule : [" + rule + "]. Only a single character is allowed.");
            }
            if (rhs == null) {
                throw new IllegalArgumentException("Invalid Mapping Rule : [" + rule + "]. Illegal type.");
            }
            typeMap.put(lhs.charAt(0), rhs);
        }
        int size = WordDelimiterIterator.DEFAULT_WORD_DELIM_TABLE.length;
        if (!typeMap.isEmpty()) {
            size = Math.max(typeMap.lastKey() + 1, size);
        }
        byte[] types = new byte[size];
        for (int i = 0; i < types.length; i++) {
            types[i] = WordDelimiterIterator.getType(i);
        }
        for (Map.Entry<Character, Byte> mapping : typeMap.entrySet()) {
            types[mapping.getKey()] = mapping.getValue();
        }
        return types;
    }

    public static Set<String> protectedWords(List<String> words) {
        if (words == null) {
            return null;
        }
        return new HashSet<>(words);
    }

    private static Byte parseType(String s) {
        return switch (s) {
            case "LOWER" -> (byte) WordDelimiterIterator.LOWER;
            case "UPPER" -> (byte) WordDelimiterIterator.UPPER;
            case "ALPHA" -> (byte) WordDelimiterIterator.ALPHA;
            case "DIGIT" -> (byte) WordDelimiterIterator.DIGIT;
            case "ALPHANUM" -> (byte) WordDelimiterIterator.ALPHANUM;
            case "SUBWORD_DELIM" -> (byte) WordDelimiterIterator.SUBWORD_DELIM;
            default -> null;
        };
    }

    private static String parseString(String s) {
        StringBuilder out = new StringBuilder();
        int readPos = 0;
        int len = s.length();
        while (readPos < len) {
            char c = s.charAt(readPos++);
            if (c == '\\') {
                if (readPos >= len) {
                    throw new IllegalArgumentException("Invalid escaped char in [" + s + "]");
                }
                c = s.charAt(readPos++);
                switch (c) {
                    case '\\' -> c = '\\';
                    case 'n' -> c = '\n';
                    case 't' -> c = '\t';
                    case 'r' -> c = '\r';
                    case 'b' -> c = '\b';
                    case 'f' -> c = '\f';
                    case 'u' -> {
                        if (readPos + 3 >= len) {
                            throw new IllegalArgumentException("Invalid escaped char in [" + s + "]");
                        }
                        try {
                            c = (char) Integer.parseInt(s.substring(readPos, readPos + 4), 16);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid escaped char in [" + s + "]", e);
                        }
                        readPos += 4;
                    }
                    default -> {
                    }
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int flag(int flag, Map<String, Object> params, String key, boolean defaultValue) {
        return bool(params, key, defaultValue) ? flag : 0;
    }

    private static boolean bool(Map<String, Object> params, String key, boolean defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = value.toString();
        if ("true".equals(s)) {
            return true;
        }
        if ("false".equals(s)) {
            return false;
        }
        throw new IllegalArgumentException("Failed to parse value [" + s + "] as only [true] or [false] are allowed.");
    }
}
