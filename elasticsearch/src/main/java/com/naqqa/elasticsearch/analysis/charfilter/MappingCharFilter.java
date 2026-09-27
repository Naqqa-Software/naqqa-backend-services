package com.naqqa.elasticsearch.analysis.charfilter;

import com.naqqa.elasticsearch.analysis.CharFilter;
import com.naqqa.elasticsearch.analysis.FilteredText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class MappingCharFilter extends CharFilter {

    private final Map<String, String> mappings = new TreeMap<>();
    private final int maxKeyLength;

    public MappingCharFilter(Map<String, String> mappings) {
        int max = 1;
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            this.mappings.put(e.getKey(), e.getValue());
            max = Math.max(max, e.getKey().length());
        }
        this.maxKeyLength = max;
    }

    public static Map<String, String> parseRules(List<String> rules) {
        Map<String, String> out = new TreeMap<>();
        if (rules == null) {
            return out;
        }
        for (String rule : rules) {
            int arrow = rule.indexOf("=>");
            if (arrow < 0) {
                throw new IllegalArgumentException("Invalid mapping rule: [" + rule + "]");
            }
            String lhs = unescape(rule.substring(0, arrow).trim());
            String rhs = unescape(rule.substring(arrow + 2).trim());
            out.put(lhs, rhs);
        }
        return out;
    }

    private static String unescape(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i++);
            if (c == '\\' && i < n) {
                char e = s.charAt(i++);
                switch (e) {
                    case '\\' -> out.append('\\');
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case '"' -> out.append('"');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> out.append(e);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    @Override
    public void filter(CharSequence input, FilteredText output) {
        int len = input.length();
        int pos = 0;
        int cumulativeDiff = 0;
        StringBuilder out = output.text();
        while (pos < len) {
            String match = null;
            String replacement = null;
            int maxLen = Math.min(maxKeyLength, len - pos);
            for (Map.Entry<String, String> e : mappings.entrySet()) {
                String key = e.getKey();
                if (key.length() > maxLen) {
                    continue;
                }
                if (match != null && key.length() <= match.length()) {
                    continue;
                }
                if (regionMatches(input, pos, key)) {
                    match = key;
                    replacement = e.getValue();
                }
            }
            if (match == null) {
                out.append(input.charAt(pos));
                pos++;
                continue;
            }
            int before = out.length();
            out.append(replacement);
            int appended = out.length() - before;
            cumulativeDiff += match.length() - appended;
            output.addOffCorrectMap(out.length(), cumulativeDiff);
            pos += match.length();
        }
    }

    private static boolean regionMatches(CharSequence input, int pos, String key) {
        int n = key.length();
        if (pos + n > input.length()) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (input.charAt(pos + i) != key.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
