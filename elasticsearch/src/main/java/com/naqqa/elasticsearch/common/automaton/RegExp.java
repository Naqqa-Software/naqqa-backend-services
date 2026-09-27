package com.naqqa.elasticsearch.common.automaton;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class RegExp {

    public static final int INTERSECTION = 0x0001;
    public static final int COMPLEMENT = 0x0002;
    public static final int EMPTY = 0x0004;
    public static final int ANYSTRING = 0x0008;
    public static final int AUTOMATON = 0x0010;
    public static final int INTERVAL = 0x0020;
    public static final int ALL = 0x00ff;
    public static final int NONE = 0x0000;
    public static final int ASCII_CASE_INSENSITIVE = 0x0100;
    public static final int CASE_INSENSITIVE = 0x0200;

    public enum Kind {
        REGEXP_UNION,
        REGEXP_CONCATENATION,
        REGEXP_INTERSECTION,
        REGEXP_OPTIONAL,
        REGEXP_REPEAT,
        REGEXP_REPEAT_MIN,
        REGEXP_REPEAT_MINMAX,
        REGEXP_COMPLEMENT,
        REGEXP_CHAR,
        REGEXP_CHAR_CLASS,
        REGEXP_ANYCHAR,
        REGEXP_EMPTY,
        REGEXP_STRING,
        REGEXP_ANYSTRING,
        REGEXP_INTERVAL
    }

    private final String originalString;
    private final int flags;
    private Kind kind;
    private RegExp exp1;
    private RegExp exp2;
    private String s;
    private int c;
    private int min;
    private int max;
    private int digits;
    private BigInteger from;
    private BigInteger to;
    private int[] ranges;

    private final int[] input;
    private int pos;

    public RegExp(String s) {
        this(s, ALL);
    }

    public RegExp(String s, int syntaxFlags) {
        this(s, syntaxFlags, 0);
    }

    public RegExp(String s, int syntaxFlags, int matchFlags) {
        this.originalString = s;
        this.flags = syntaxFlags | matchFlags;
        this.input = s.codePoints().toArray();
        this.pos = 0;
        RegExp e;
        if (input.length == 0) {
            e = makeNode(Kind.REGEXP_STRING);
            e.s = "";
        } else {
            e = parseUnionExp();
            if (pos < input.length) {
                throw new IllegalArgumentException("end-of-string expected at position " + pos + " in [" + s + "]");
            }
        }
        copyFrom(e);
    }

    private RegExp(int flags) {
        this.originalString = null;
        this.flags = flags;
        this.input = null;
    }

    private RegExp makeNode(Kind k) {
        RegExp r = new RegExp(flags);
        r.kind = k;
        return r;
    }

    private void copyFrom(RegExp e) {
        this.kind = e.kind;
        this.exp1 = e.exp1;
        this.exp2 = e.exp2;
        this.s = e.s;
        this.c = e.c;
        this.min = e.min;
        this.max = e.max;
        this.digits = e.digits;
        this.from = e.from;
        this.to = e.to;
        this.ranges = e.ranges;
    }

    public static int parseFlags(String flagsString) {
        if (flagsString == null || flagsString.isBlank()) {
            return ALL;
        }
        int result = 0;
        for (String part : flagsString.split("\\|")) {
            String f = part.trim().toUpperCase(Locale.ROOT);
            if (f.isEmpty()) {
                continue;
            }
            switch (f) {
                case "INTERSECTION" -> result |= INTERSECTION;
                case "COMPLEMENT" -> result |= COMPLEMENT;
                case "EMPTY" -> result |= EMPTY;
                case "ANYSTRING" -> result |= ANYSTRING;
                case "INTERVAL" -> result |= INTERVAL;
                case "ALL" -> result |= ALL;
                case "NONE" -> result |= NONE;
                default -> throw new IllegalArgumentException("Unknown regexp flag [" + part.trim() + "]");
            }
        }
        return result;
    }

    public Kind getKind() {
        return kind;
    }

    public String getOriginalString() {
        return originalString;
    }

    public int getFlags() {
        return flags;
    }

    public Automaton toAutomaton() {
        return toAutomaton(Operations.DEFAULT_MAX_DETERMINIZED_STATES);
    }

    public Automaton toAutomaton(int maxDeterminizedStates) {
        try {
            Automaton a = toNfa(maxDeterminizedStates);
            return Operations.minimize(a, maxDeterminizedStates);
        } catch (TooComplexToDeterminizeException e) {
            throw new TooComplexToDeterminizeException(originalString == null ? toString() : originalString, maxDeterminizedStates);
        }
    }

    public Automaton toNfa(int maxDeterminizedStates) {
        switch (kind) {
            case REGEXP_UNION: {
                List<Automaton> list = new ArrayList<>();
                collectUnion(this, list, maxDeterminizedStates);
                return Operations.union(list);
            }
            case REGEXP_CONCATENATION: {
                List<Automaton> list = new ArrayList<>();
                collectConcat(this, list, maxDeterminizedStates);
                return Operations.concatenate(list);
            }
            case REGEXP_INTERSECTION:
                return Operations.intersection(exp1.toNfa(maxDeterminizedStates), exp2.toNfa(maxDeterminizedStates));
            case REGEXP_OPTIONAL:
                return Operations.optional(exp1.toNfa(maxDeterminizedStates));
            case REGEXP_REPEAT:
                return Operations.repeat(exp1.toNfa(maxDeterminizedStates));
            case REGEXP_REPEAT_MIN:
                return Operations.repeat(exp1.toNfa(maxDeterminizedStates), min);
            case REGEXP_REPEAT_MINMAX:
                return Operations.repeat(exp1.toNfa(maxDeterminizedStates), min, max);
            case REGEXP_COMPLEMENT: {
                Automaton inner = Operations.minimize(exp1.toNfa(maxDeterminizedStates), maxDeterminizedStates);
                return Operations.complement(inner, maxDeterminizedStates);
            }
            case REGEXP_CHAR:
                return charAutomaton(c);
            case REGEXP_CHAR_CLASS:
                return Automata.makeCharSet(caseFold(ranges));
            case REGEXP_ANYCHAR:
                return Automata.makeAnyChar();
            case REGEXP_EMPTY:
                return Automata.makeEmpty();
            case REGEXP_STRING: {
                if (!caseInsensitive()) {
                    return Automata.makeString(s);
                }
                List<Automaton> list = new ArrayList<>();
                s.codePoints().forEach(cp -> list.add(charAutomaton(cp)));
                return Operations.concatenate(list);
            }
            case REGEXP_ANYSTRING:
                return Automata.makeAnyString();
            case REGEXP_INTERVAL:
                return Automata.makeDecimalInterval(from, to, digits);
            default:
                throw new IllegalStateException("unknown kind " + kind);
        }
    }

    private static void collectUnion(RegExp e, List<Automaton> list, int maxStates) {
        if (e.kind == Kind.REGEXP_UNION) {
            collectUnion(e.exp1, list, maxStates);
            collectUnion(e.exp2, list, maxStates);
        } else {
            list.add(e.toNfa(maxStates));
        }
    }

    private static void collectConcat(RegExp e, List<Automaton> list, int maxStates) {
        if (e.kind == Kind.REGEXP_CONCATENATION) {
            collectConcat(e.exp1, list, maxStates);
            collectConcat(e.exp2, list, maxStates);
        } else {
            list.add(e.toNfa(maxStates));
        }
    }

    private boolean caseInsensitive() {
        return (flags & (ASCII_CASE_INSENSITIVE | CASE_INSENSITIVE)) != 0;
    }

    private Automaton charAutomaton(int cp) {
        if (!caseInsensitive()) {
            return Automata.makeChar(cp);
        }
        int[] variants = caseVariants(cp);
        if (variants.length == 1) {
            return Automata.makeChar(cp);
        }
        int[] r = new int[variants.length * 2];
        for (int i = 0; i < variants.length; i++) {
            r[i * 2] = variants[i];
            r[i * 2 + 1] = variants[i];
        }
        return Automata.makeCharSet(normalizeRanges(r));
    }

    private int[] caseVariants(int cp) {
        boolean asciiOnly = (flags & CASE_INSENSITIVE) == 0;
        if (asciiOnly && cp >= 128) {
            return new int[] {cp};
        }
        int lower = Character.toLowerCase(cp);
        int upper = Character.toUpperCase(cp);
        int title = Character.toTitleCase(cp);
        if (asciiOnly) {
            if (lower >= 128) {
                lower = cp;
            }
            if (upper >= 128) {
                upper = cp;
            }
            title = cp;
        }
        int[] all = {cp, lower, upper, title};
        Arrays.sort(all);
        int n = 0;
        for (int i = 0; i < all.length; i++) {
            if (i == 0 || all[i] != all[i - 1]) {
                all[n++] = all[i];
            }
        }
        return Arrays.copyOf(all, n);
    }

    private int[] caseFold(int[] r) {
        if (!caseInsensitive()) {
            return r;
        }
        List<Integer> extra = new ArrayList<>();
        boolean asciiOnly = (flags & CASE_INSENSITIVE) == 0;
        for (int i = 0; i < r.length; i += 2) {
            int lo = r[i];
            int hi = r[i + 1];
            if (asciiOnly) {
                hi = Math.min(hi, 127);
            }
            for (int cp = lo; cp <= hi; cp++) {
                for (int v : caseVariants(cp)) {
                    if (v != cp) {
                        extra.add(v);
                    }
                }
            }
        }
        int[] all = Arrays.copyOf(r, r.length + extra.size() * 2);
        for (int i = 0; i < extra.size(); i++) {
            all[r.length + i * 2] = extra.get(i);
            all[r.length + i * 2 + 1] = extra.get(i);
        }
        return normalizeRanges(all);
    }

    static int[] normalizeRanges(int[] r) {
        int n = r.length / 2;
        long[] packed = new long[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (r[i * 2] <= r[i * 2 + 1]) {
                packed[m++] = ((long) r[i * 2] << 32) | r[i * 2 + 1];
            }
        }
        Arrays.sort(packed, 0, m);
        int[] out = new int[m * 2];
        int k = 0;
        for (int i = 0; i < m; i++) {
            int lo = (int) (packed[i] >>> 32);
            int hi = (int) packed[i];
            if (k > 0 && lo <= out[k - 1] + 1) {
                out[k - 1] = Math.max(out[k - 1], hi);
            } else {
                out[k++] = lo;
                out[k++] = hi;
            }
        }
        return Arrays.copyOf(out, k);
    }

    static int[] negateRanges(int[] r) {
        r = normalizeRanges(r);
        List<Integer> out = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < r.length; i += 2) {
            if (r[i] > next) {
                out.add(next);
                out.add(r[i] - 1);
            }
            next = r[i + 1] + 1;
        }
        if (next <= Automaton.MAX_CODE_POINT) {
            out.add(next);
            out.add(Automaton.MAX_CODE_POINT);
        }
        int[] res = new int[out.size()];
        for (int i = 0; i < res.length; i++) {
            res[i] = out.get(i);
        }
        return res;
    }

    private boolean more() {
        return pos < input.length;
    }

    private boolean peek(String chars) {
        return more() && chars.indexOf(input[pos]) != -1;
    }

    private boolean match(int ch) {
        if (pos >= input.length) {
            return false;
        }
        if (input[pos] == ch) {
            pos++;
            return true;
        }
        return false;
    }

    private int next() {
        if (!more()) {
            throw new IllegalArgumentException("unexpected end-of-string in [" + str() + "]");
        }
        return input[pos++];
    }

    private String str() {
        return new String(input, 0, input.length);
    }

    private boolean check(int flag) {
        return (flags & flag) != 0;
    }

    private RegExp binary(Kind k, RegExp a, RegExp b) {
        RegExp r = makeNode(k);
        r.exp1 = a;
        r.exp2 = b;
        return r;
    }

    private RegExp unary(Kind k, RegExp a) {
        RegExp r = makeNode(k);
        r.exp1 = a;
        return r;
    }

    private RegExp parseUnionExp() {
        RegExp e = parseInterExp();
        if (match('|')) {
            e = binary(Kind.REGEXP_UNION, e, parseUnionExp());
        }
        return e;
    }

    private RegExp parseInterExp() {
        RegExp e = parseConcatExp();
        if (check(INTERSECTION) && match('&')) {
            e = binary(Kind.REGEXP_INTERSECTION, e, parseInterExp());
        }
        return e;
    }

    private RegExp parseConcatExp() {
        RegExp e = parseRepeatExp();
        if (more() && !peek(")|") && (!check(INTERSECTION) || !peek("&"))) {
            e = makeConcat(e, parseConcatExp());
        }
        return e;
    }

    private RegExp makeConcat(RegExp a, RegExp b) {
        if ((a.kind == Kind.REGEXP_CHAR || a.kind == Kind.REGEXP_STRING) && (b.kind == Kind.REGEXP_CHAR || b.kind == Kind.REGEXP_STRING)) {
            RegExp r = makeNode(Kind.REGEXP_STRING);
            r.s = text(a) + text(b);
            return r;
        }
        return binary(Kind.REGEXP_CONCATENATION, a, b);
    }

    private static String text(RegExp r) {
        return r.kind == Kind.REGEXP_CHAR ? new String(Character.toChars(r.c)) : r.s;
    }

    private RegExp parseRepeatExp() {
        RegExp e = parseComplExp();
        while (peek("?*+{")) {
            if (match('?')) {
                e = unary(Kind.REGEXP_OPTIONAL, e);
            } else if (match('*')) {
                e = unary(Kind.REGEXP_REPEAT, e);
            } else if (match('+')) {
                e = unary(Kind.REGEXP_REPEAT_MIN, e);
                e.min = 1;
            } else if (match('{')) {
                int start = pos;
                while (peek("0123456789")) {
                    next();
                }
                if (start == pos) {
                    throw new IllegalArgumentException("integer expected at position " + pos + " in [" + str() + "]");
                }
                int n = Integer.parseInt(new String(input, start, pos - start));
                int m = -1;
                if (match(',')) {
                    start = pos;
                    while (peek("0123456789")) {
                        next();
                    }
                    if (start != pos) {
                        m = Integer.parseInt(new String(input, start, pos - start));
                    }
                } else {
                    m = n;
                }
                if (!match('}')) {
                    throw new IllegalArgumentException("expected '}' at position " + pos + " in [" + str() + "]");
                }
                if (m == -1) {
                    e = unary(Kind.REGEXP_REPEAT_MIN, e);
                    e.min = n;
                } else {
                    if (m < n) {
                        throw new IllegalArgumentException("invalid repetition range {" + n + "," + m + "} in [" + str() + "]");
                    }
                    e = unary(Kind.REGEXP_REPEAT_MINMAX, e);
                    e.min = n;
                    e.max = m;
                }
            }
        }
        return e;
    }

    private RegExp parseComplExp() {
        if (check(COMPLEMENT) && match('~')) {
            return unary(Kind.REGEXP_COMPLEMENT, parseComplExp());
        }
        return parseCharClassExp();
    }

    private RegExp parseCharClassExp() {
        if (match('[')) {
            boolean negate = match('^');
            List<Integer> r = new ArrayList<>();
            boolean firstItem = true;
            while (more() && (firstItem || input[pos] != ']')) {
                parseCharClass(r);
                firstItem = false;
            }
            if (!match(']')) {
                throw new IllegalArgumentException("expected ']' at position " + pos + " in [" + str() + "]");
            }
            int[] arr = new int[r.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = r.get(i);
            }
            if (negate) {
                return makeNodeNoFold(negateRanges(caseFold(normalizeRanges(arr))));
            }
            RegExp e = makeNode(Kind.REGEXP_CHAR_CLASS);
            e.ranges = normalizeRanges(arr);
            return e;
        }
        return parseSimpleExp();
    }

    private RegExp makeNodeNoFold(int[] r) {
        RegExp e = new RegExp(flags & ~(ASCII_CASE_INSENSITIVE | CASE_INSENSITIVE));
        e.kind = Kind.REGEXP_CHAR_CLASS;
        e.ranges = r;
        return e;
    }

    private void parseCharClass(List<Integer> r) {
        if (more() && input[pos] == '\\' && pos + 1 < input.length && isShorthand(input[pos + 1])) {
            pos++;
            int[] sh = shorthand(next());
            for (int v : sh) {
                r.add(v);
            }
            return;
        }
        int lo = parseCharExp();
        if (more() && input[pos] == '-' && pos + 1 < input.length && input[pos + 1] != ']') {
            pos++;
            int hi = parseCharExp();
            if (hi < lo) {
                throw new IllegalArgumentException("invalid character class range [" + new String(Character.toChars(lo)) + "-" + new String(Character.toChars(hi)) + "] in [" + str() + "]");
            }
            r.add(lo);
            r.add(hi);
        } else {
            r.add(lo);
            r.add(lo);
        }
    }

    private static boolean isShorthand(int ch) {
        return ch == 'd' || ch == 'D' || ch == 'w' || ch == 'W' || ch == 's' || ch == 'S';
    }

    private static int[] shorthand(int ch) {
        int[] base;
        switch (Character.toLowerCase(ch)) {
            case 'd' -> base = new int[] {'0', '9'};
            case 'w' -> base = new int[] {'0', '9', 'A', 'Z', '_', '_', 'a', 'z'};
            default -> base = new int[] {'\t', '\r', ' ', ' '};
        }
        if (Character.isUpperCase(ch)) {
            return negateRanges(base);
        }
        return base;
    }

    private RegExp parseSimpleExp() {
        if (match('.')) {
            return makeNode(Kind.REGEXP_ANYCHAR);
        }
        if (check(EMPTY) && match('#')) {
            return makeNode(Kind.REGEXP_EMPTY);
        }
        if (check(ANYSTRING) && match('@')) {
            return makeNode(Kind.REGEXP_ANYSTRING);
        }
        if (match('"')) {
            int start = pos;
            while (more() && input[pos] != '"') {
                pos++;
            }
            if (!match('"')) {
                throw new IllegalArgumentException("expected '\"' at position " + pos + " in [" + str() + "]");
            }
            RegExp e = makeNode(Kind.REGEXP_STRING);
            e.s = new String(input, start, pos - 1 - start);
            return e;
        }
        if (match('(')) {
            if (match(')')) {
                RegExp e = makeNode(Kind.REGEXP_STRING);
                e.s = "";
                return e;
            }
            RegExp e = parseUnionExp();
            if (!match(')')) {
                throw new IllegalArgumentException("expected ')' at position " + pos + " in [" + str() + "]");
            }
            return e;
        }
        if (check(INTERVAL) && peek("<")) {
            int save = pos;
            pos++;
            int start = pos;
            while (more() && input[pos] != '>') {
                pos++;
            }
            if (!more()) {
                throw new IllegalArgumentException("expected '>' at position " + pos + " in [" + str() + "]");
            }
            String body = new String(input, start, pos - start);
            pos++;
            int dash = body.indexOf('-');
            if (dash == -1) {
                if ((flags & AUTOMATON) != 0) {
                    throw new IllegalArgumentException("named automata are not supported: <" + body + "> in [" + str() + "]");
                }
                pos = save;
                return parseCharLiteral();
            }
            String smin = body.substring(0, dash);
            String smax = body.substring(dash + 1);
            if (smin.isEmpty() || smax.isEmpty() || !smin.chars().allMatch(Character::isDigit) || !smax.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("interval syntax error at position " + (pos - 1) + " in [" + str() + "]");
            }
            BigInteger imin = new BigInteger(smin);
            BigInteger imax = new BigInteger(smax);
            RegExp e = makeNode(Kind.REGEXP_INTERVAL);
            e.digits = smin.length() == smax.length() ? smin.length() : 0;
            if (imin.compareTo(imax) > 0) {
                BigInteger t = imin;
                imin = imax;
                imax = t;
            }
            e.from = imin;
            e.to = imax;
            return e;
        }
        if (more() && input[pos] == '\\' && pos + 1 < input.length && isShorthand(input[pos + 1])) {
            pos++;
            int[] sh = shorthand(next());
            RegExp e = makeNodeNoFold(sh);
            return e;
        }
        return parseCharLiteral();
    }

    private RegExp parseCharLiteral() {
        RegExp e = makeNode(Kind.REGEXP_CHAR);
        e.c = parseCharExp();
        return e;
    }

    private int parseCharExp() {
        match('\\');
        return next();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        toStringBuilder(sb);
        return sb.toString();
    }

    private void toStringBuilder(StringBuilder b) {
        switch (kind) {
            case REGEXP_UNION -> {
                b.append('(');
                exp1.toStringBuilder(b);
                b.append('|');
                exp2.toStringBuilder(b);
                b.append(')');
            }
            case REGEXP_CONCATENATION -> {
                exp1.toStringBuilder(b);
                exp2.toStringBuilder(b);
            }
            case REGEXP_INTERSECTION -> {
                b.append('(');
                exp1.toStringBuilder(b);
                b.append('&');
                exp2.toStringBuilder(b);
                b.append(')');
            }
            case REGEXP_OPTIONAL -> {
                b.append('(');
                exp1.toStringBuilder(b);
                b.append(")?");
            }
            case REGEXP_REPEAT -> {
                b.append('(');
                exp1.toStringBuilder(b);
                b.append(")*");
            }
            case REGEXP_REPEAT_MIN -> {
                b.append('(');
                exp1.toStringBuilder(b);
                b.append("){").append(min).append(",}");
            }
            case REGEXP_REPEAT_MINMAX -> {
                b.append('(');
                exp1.toStringBuilder(b);
                b.append("){").append(min).append(',').append(max).append('}');
            }
            case REGEXP_COMPLEMENT -> {
                b.append("~(");
                exp1.toStringBuilder(b);
                b.append(')');
            }
            case REGEXP_CHAR -> b.append('\\').appendCodePoint(c);
            case REGEXP_CHAR_CLASS -> {
                b.append('[');
                for (int i = 0; i < ranges.length; i += 2) {
                    b.append('\\').appendCodePoint(ranges[i]);
                    if (ranges[i + 1] != ranges[i]) {
                        b.append("-\\").appendCodePoint(ranges[i + 1]);
                    }
                }
                b.append(']');
            }
            case REGEXP_ANYCHAR -> b.append('.');
            case REGEXP_EMPTY -> b.append('#');
            case REGEXP_STRING -> {
                if (s.isEmpty()) {
                    b.append("()");
                } else {
                    s.codePoints().forEach(cp -> b.append('\\').appendCodePoint(cp));
                }
            }
            case REGEXP_ANYSTRING -> b.append('@');
            case REGEXP_INTERVAL -> {
                String a = from.toString();
                String z = to.toString();
                if (digits > 0) {
                    while (a.length() < digits) {
                        a = "0" + a;
                    }
                    while (z.length() < digits) {
                        z = "0" + z;
                    }
                }
                b.append('<').append(a).append('-').append(z).append('>');
            }
            default -> throw new IllegalStateException();
        }
    }
}
