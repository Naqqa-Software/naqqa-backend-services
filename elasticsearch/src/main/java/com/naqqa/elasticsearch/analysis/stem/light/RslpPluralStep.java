package com.naqqa.elasticsearch.analysis.stem.light;

import java.util.Set;

final class RslpPluralStep {

    static final class Rule {

        final String suffix;
        final int min;
        final String replacement;
        final Set<String> exceptions;

        Rule(String suffix, int min, String replacement, String... exceptions) {
            this.suffix = suffix;
            this.min = min;
            this.replacement = replacement;
            this.exceptions = Set.of(exceptions);
        }

        boolean matches(char[] s, int len) {
            if (len - suffix.length() < min || !CharArrayStemmer.endsWith(s, len, suffix)) {
                return false;
            }
            return exceptions.isEmpty() || !exceptions.contains(new String(s, 0, len));
        }

        int replace(char[] s, int len) {
            int start = len - suffix.length();
            for (int i = 0; i < replacement.length(); i++) {
                s[start + i] = replacement.charAt(i);
            }
            return start + replacement.length();
        }
    }

    private final int min;
    private final Rule[] rules;

    RslpPluralStep(int min, Rule... rules) {
        this.min = min;
        this.rules = rules;
    }

    int apply(char[] s, int len) {
        if (len < min || len == 0 || s[len - 1] != 's') {
            return len;
        }
        for (Rule rule : rules) {
            if (rule.matches(s, len)) {
                return rule.replace(s, len);
            }
        }
        return len;
    }
}
