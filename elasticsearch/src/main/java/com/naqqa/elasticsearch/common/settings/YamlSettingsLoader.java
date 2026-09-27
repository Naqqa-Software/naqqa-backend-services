package com.naqqa.elasticsearch.common.settings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public final class YamlSettingsLoader {

    private YamlSettingsLoader() {
    }

    private static final class Level {
        final int indent;
        final String path;

        Level(int indent, String path) {
            this.indent = indent;
            this.path = path;
        }
    }

    public static Settings load(String content) {
        Settings.Builder builder = Settings.builder();
        Map<String, Integer> listCounters = new HashMap<>();
        Deque<Level> stack = new ArrayDeque<>();
        stack.push(new Level(-1, ""));
        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = stripComment(rawLine);
            if (line.trim().isEmpty()) {
                continue;
            }
            int indent = countIndent(line);
            String trimmed = line.trim();
            while (stack.size() > 1 && indent <= stack.peek().indent) {
                stack.pop();
            }
            String parentPath = stack.peek().path;

            if (trimmed.startsWith("- ")) {
                String value = unquote(trimmed.substring(2).trim());
                int nextIndex = listCounters.merge(parentPath, 1, Integer::sum) - 1;
                builder.put(parentPath + "." + nextIndex, value);
                continue;
            }

            int colon = findColon(trimmed);
            if (colon < 0) {
                continue;
            }
            String key = unquote(trimmed.substring(0, colon).trim());
            String value = trimmed.substring(colon + 1).trim();
            String path = parentPath.isEmpty() ? key : parentPath + "." + key;

            if (value.isEmpty()) {
                stack.push(new Level(indent, path));
            } else {
                builder.put(path, unquote(value));
            }
        }
        return builder.build();
    }

    private static int findColon(String s) {
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
            } else if (c == ':' && (i + 1 == s.length() || s.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String line) {
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int countIndent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
