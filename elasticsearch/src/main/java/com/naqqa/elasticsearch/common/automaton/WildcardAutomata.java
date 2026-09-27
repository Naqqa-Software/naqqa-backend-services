package com.naqqa.elasticsearch.common.automaton;

import java.util.ArrayList;
import java.util.List;

public final class WildcardAutomata {

    public static final char WILDCARD_STRING = '*';
    public static final char WILDCARD_CHAR = '?';
    public static final char WILDCARD_ESCAPE = '\\';

    private WildcardAutomata() {
    }

    public static Automaton toAutomaton(String pattern) {
        return toAutomaton(pattern, false);
    }

    public static Automaton toAutomaton(String pattern, boolean caseInsensitive) {
        List<Automaton> parts = new ArrayList<>();
        int[] cps = pattern.codePoints().toArray();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < cps.length; i++) {
            int c = cps[i];
            if (c == WILDCARD_STRING || c == WILDCARD_CHAR) {
                flush(literal, parts, caseInsensitive);
                parts.add(c == WILDCARD_STRING ? Automata.makeAnyString() : Automata.makeAnyChar());
            } else if (c == WILDCARD_ESCAPE) {
                if (i + 1 < cps.length) {
                    literal.appendCodePoint(cps[++i]);
                } else {
                    literal.appendCodePoint(c);
                }
            } else {
                literal.appendCodePoint(c);
            }
        }
        flush(literal, parts, caseInsensitive);
        return Operations.minimize(Operations.concatenate(parts), Integer.MAX_VALUE);
    }

    private static void flush(StringBuilder literal, List<Automaton> parts, boolean caseInsensitive) {
        if (literal.isEmpty()) {
            return;
        }
        String s = literal.toString();
        literal.setLength(0);
        if (!caseInsensitive) {
            parts.add(Automata.makeString(s));
            return;
        }
        s.codePoints().forEach(cp -> {
            int lower = Character.toLowerCase(cp);
            int upper = Character.toUpperCase(cp);
            int title = Character.toTitleCase(cp);
            int[] r = {cp, cp, lower, lower, upper, upper, title, title};
            parts.add(Automata.makeCharSet(RegExp.normalizeRanges(r)));
        });
    }

    public static String unescape(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == WILDCARD_ESCAPE && i + 1 < pattern.length()) {
                sb.append(pattern.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static Automaton prefix(String prefix) {
        return Operations.minimize(Automata.makePrefix(prefix), Integer.MAX_VALUE);
    }

    public static Automaton prefix(String prefix, boolean caseInsensitive) {
        if (!caseInsensitive) {
            return prefix(prefix);
        }
        StringBuilder escaped = new StringBuilder();
        prefix.codePoints().forEach(cp -> {
            if (cp == WILDCARD_STRING || cp == WILDCARD_CHAR || cp == WILDCARD_ESCAPE) {
                escaped.append(WILDCARD_ESCAPE);
            }
            escaped.appendCodePoint(cp);
        });
        escaped.append(WILDCARD_STRING);
        return toAutomaton(escaped.toString(), true);
    }

    public static Automaton binaryPrefix(byte[] prefix) {
        return Operations.minimize(Automata.makeBinaryPrefix(prefix), Integer.MAX_VALUE);
    }
}
