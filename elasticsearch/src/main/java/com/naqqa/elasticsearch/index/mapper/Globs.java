package com.naqqa.elasticsearch.index.mapper;

final class Globs {

    private Globs() {
    }

    static boolean match(String pattern, String value) {
        if (pattern.equals(value)) {
            return true;
        }
        if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
            return false;
        }
        return value.matches(toRegex(pattern));
    }

    static String toRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.' -> sb.append("\\.");
                default -> {
                    if ("\\^$|()[]{}+".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
