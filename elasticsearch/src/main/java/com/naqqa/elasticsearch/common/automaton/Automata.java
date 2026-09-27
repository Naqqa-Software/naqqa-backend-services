package com.naqqa.elasticsearch.common.automaton;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public final class Automata {

    private Automata() {
    }

    public static Automaton makeEmpty() {
        Automaton a = new Automaton();
        a.createState();
        return a;
    }

    public static Automaton makeEmptyString() {
        Automaton a = new Automaton();
        a.createState();
        a.setAccept(0, true);
        return a;
    }

    public static Automaton makeAnyString() {
        Automaton a = new Automaton();
        int s = a.createState();
        a.setAccept(s, true);
        a.addTransition(s, s, 0, Automaton.MAX_CODE_POINT);
        return a;
    }

    public static Automaton makeAnyBinary() {
        Automaton a = new Automaton();
        int s = a.createState();
        a.setAccept(s, true);
        a.addTransition(s, s, 0, Automaton.MAX_BYTE);
        return a;
    }

    public static Automaton makeNonEmptyBinary() {
        Automaton a = new Automaton();
        int s0 = a.createState();
        int s1 = a.createState();
        a.setAccept(s1, true);
        a.addTransition(s0, s1, 0, Automaton.MAX_BYTE);
        a.addTransition(s1, s1, 0, Automaton.MAX_BYTE);
        return a;
    }

    public static Automaton makeAnyChar() {
        return makeCharRange(0, Automaton.MAX_CODE_POINT);
    }

    public static Automaton makeAnyByte() {
        return makeCharRange(0, Automaton.MAX_BYTE);
    }

    public static Automaton makeChar(int c) {
        return makeCharRange(c, c);
    }

    public static Automaton makeCharRange(int min, int max) {
        if (min > max) {
            return makeEmpty();
        }
        Automaton a = new Automaton();
        int s0 = a.createState();
        int s1 = a.createState();
        a.setAccept(s1, true);
        a.addTransition(s0, s1, min, max);
        return a;
    }

    public static Automaton makeCharSet(int[] ranges) {
        Automaton a = new Automaton();
        int s0 = a.createState();
        int s1 = a.createState();
        a.setAccept(s1, true);
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            if (ranges[i] <= ranges[i + 1]) {
                a.addTransition(s0, s1, ranges[i], ranges[i + 1]);
            }
        }
        return a;
    }

    public static Automaton makeString(String s) {
        return makeString(s.codePoints().toArray());
    }

    public static Automaton makeString(int[] codePoints) {
        return makeLabels(codePoints, 0, codePoints.length);
    }

    public static Automaton makeBinary(byte[] bytes) {
        int[] labels = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            labels[i] = bytes[i] & 0xFF;
        }
        return makeLabels(labels, 0, labels.length);
    }

    public static Automaton makeLabels(int[] labels, int offset, int length) {
        Automaton a = new Automaton();
        int last = a.createState();
        for (int i = offset; i < offset + length; i++) {
            int next = a.createState();
            a.addTransition(last, next, labels[i]);
            last = next;
        }
        a.setAccept(last, true);
        a.finish();
        return a;
    }

    public static Automaton makeStringUnion(Collection<String> strings) {
        List<int[]> list = new ArrayList<>();
        for (String s : strings) {
            list.add(s.codePoints().toArray());
        }
        return makeLabelsUnion(list);
    }

    public static Automaton makeBinaryUnion(Collection<byte[]> terms) {
        List<int[]> list = new ArrayList<>();
        for (byte[] b : terms) {
            int[] labels = new int[b.length];
            for (int i = 0; i < b.length; i++) {
                labels[i] = b[i] & 0xFF;
            }
            list.add(labels);
        }
        return makeLabelsUnion(list);
    }

    private static Automaton makeLabelsUnion(List<int[]> list) {
        Automaton trie = new Automaton();
        trie.createState();
        List<int[]> children = new ArrayList<>();
        children.add(new int[0]);
        for (int[] word : list) {
            int state = 0;
            for (int c : word) {
                int next = -1;
                int[] kids = children.get(state);
                for (int i = 0; i < kids.length; i += 2) {
                    if (kids[i] == c) {
                        next = kids[i + 1];
                        break;
                    }
                }
                if (next == -1) {
                    next = trie.createState();
                    children.add(new int[0]);
                    int[] grown = Arrays.copyOf(kids, kids.length + 2);
                    grown[kids.length] = c;
                    grown[kids.length + 1] = next;
                    children.set(state, grown);
                    trie.addTransition(state, next, c);
                }
                state = next;
            }
            trie.setAccept(state, true);
        }
        trie.finish();
        return Operations.minimize(trie, Integer.MAX_VALUE);
    }

    public static Automaton makeDecimalInterval(int min, int max, int digits) {
        return makeDecimalInterval(BigInteger.valueOf(min), BigInteger.valueOf(max), digits);
    }

    public static Automaton makeDecimalInterval(BigInteger min, BigInteger max, int digits) {
        if (min.signum() < 0 || max.signum() < 0) {
            throw new IllegalArgumentException("interval bounds must be non-negative");
        }
        if (min.compareTo(max) > 0) {
            BigInteger t = min;
            min = max;
            max = t;
        }
        if (digits > 0) {
            String hi = max.toString();
            if (hi.length() > digits) {
                return makeEmpty();
            }
            String lo = pad(min.toString(), digits);
            return fixedRange(lo, pad(hi, digits));
        }
        List<Automaton> parts = new ArrayList<>();
        int minLen = min.toString().length();
        int maxLen = max.toString().length();
        for (int len = minLen; len <= maxLen; len++) {
            BigInteger low = len == 1 ? BigInteger.ZERO : BigInteger.TEN.pow(len - 1);
            BigInteger high = BigInteger.TEN.pow(len).subtract(BigInteger.ONE);
            if (low.compareTo(min) < 0) {
                low = min;
            }
            if (high.compareTo(max) > 0) {
                high = max;
            }
            if (low.compareTo(high) <= 0) {
                parts.add(fixedRange(low.toString(), high.toString()));
            }
        }
        Automaton numbers = Operations.union(parts);
        Automaton zeros = Operations.repeat(makeChar('0'));
        return Operations.concatenate(zeros, numbers);
    }

    private static String pad(String s, int digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < digits; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    private static Automaton fixedRange(String lo, String hi) {
        Automaton a = new Automaton();
        int start = a.createState();
        int end = a.createState();
        a.setAccept(end, true);
        buildFixed(a, start, end, lo, hi, 0, true, true);
        a.finish();
        return a;
    }

    private static void buildFixed(Automaton a, int state, int end, String lo, String hi, int pos, boolean tightLo, boolean tightHi) {
        int len = lo.length();
        if (pos == len) {
            a.setAccept(state, true);
            return;
        }
        int loDigit = tightLo ? lo.charAt(pos) : '0';
        int hiDigit = tightHi ? hi.charAt(pos) : '9';
        boolean last = pos == len - 1;
        if (!tightLo && !tightHi) {
            int next = last ? end : a.createState();
            a.addTransition(state, next, '0', '9');
            if (!last) {
                buildFixed(a, next, end, lo, hi, pos + 1, false, false);
            }
            return;
        }
        if (loDigit == hiDigit) {
            int next = last ? end : a.createState();
            a.addTransition(state, next, loDigit);
            if (!last) {
                buildFixed(a, next, end, lo, hi, pos + 1, tightLo, tightHi);
            }
            return;
        }
        if (loDigit > hiDigit) {
            return;
        }
        int nextLo = last ? end : a.createState();
        a.addTransition(state, nextLo, loDigit);
        if (!last) {
            buildFixed(a, nextLo, end, lo, hi, pos + 1, tightLo, false);
        }
        if (loDigit + 1 <= hiDigit - 1) {
            int nextMid = last ? end : a.createState();
            a.addTransition(state, nextMid, loDigit + 1, hiDigit - 1);
            if (!last) {
                buildFixed(a, nextMid, end, lo, hi, pos + 1, false, false);
            }
        }
        int nextHi = last ? end : a.createState();
        a.addTransition(state, nextHi, hiDigit);
        if (!last) {
            buildFixed(a, nextHi, end, lo, hi, pos + 1, false, tightHi);
        }
    }

    public static Automaton makePrefix(String prefix) {
        return Operations.concatenate(makeString(prefix), makeAnyString());
    }

    public static Automaton makeBinaryPrefix(byte[] prefix) {
        return Operations.concatenate(makeBinary(prefix), makeAnyBinary());
    }

    public static Automaton makeUtf8Prefix(String prefix) {
        return makeBinaryPrefix(prefix.getBytes(StandardCharsets.UTF_8));
    }
}
