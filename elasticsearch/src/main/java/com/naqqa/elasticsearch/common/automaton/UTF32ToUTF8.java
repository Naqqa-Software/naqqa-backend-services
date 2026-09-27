package com.naqqa.elasticsearch.common.automaton;

import java.util.ArrayList;
import java.util.List;

public final class UTF32ToUTF8 {

    private static final int[] MAX_BY_LENGTH = {0x7F, 0x7FF, 0xFFFF, 0x10FFFF};

    public UTF32ToUTF8() {
    }

    public Automaton convert(Automaton utf32) {
        return convert(utf32, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
    }

    public Automaton convert(Automaton utf32, int maxDeterminizedStates) {
        Automaton in = Operations.removeEpsilons(utf32);
        Automaton out = new Automaton();
        int n = in.getNumStates();
        for (int s = 0; s < n; s++) {
            out.createState();
            out.setAccept(s, in.isAccept(s));
        }
        List<int[]> seqs = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            int nt = in.getNumTransitions(s);
            for (int i = 0; i < nt; i++) {
                int dest = in.getDest(s, i);
                seqs.clear();
                sequences(in.getMin(s, i), in.getMax(s, i), seqs);
                for (int[] seq : seqs) {
                    int cur = s;
                    int len = seq.length / 2;
                    for (int b = 0; b < len; b++) {
                        int target = b == len - 1 ? dest : out.createState();
                        out.addTransition(cur, target, seq[b * 2], seq[b * 2 + 1]);
                        cur = target;
                    }
                }
            }
        }
        out.finish();
        Automaton det = Operations.determinize(out, maxDeterminizedStates);
        return Operations.removeDeadStates(det);
    }

    public static void sequences(int lo, int hi, List<int[]> out) {
        if (lo > hi) {
            return;
        }
        for (int i = 0; i < MAX_BY_LENGTH.length - 1; i++) {
            int max = MAX_BY_LENGTH[i];
            if (lo <= max && max < hi) {
                sequences(lo, max, out);
                sequences(max + 1, hi, out);
                return;
            }
        }
        if (hi <= 0x7F) {
            out.add(new int[] {lo, hi});
            return;
        }
        int len = encodedLength(lo);
        for (int i = 1; i < len; i++) {
            int m = (1 << (6 * i)) - 1;
            if ((lo & ~m) != (hi & ~m)) {
                if ((lo & m) != 0) {
                    sequences(lo, lo | m, out);
                    sequences((lo | m) + 1, hi, out);
                    return;
                }
                if ((hi & m) != m) {
                    sequences(lo, (hi & ~m) - 1, out);
                    sequences(hi & ~m, hi, out);
                    return;
                }
            }
        }
        int[] a = encode(lo);
        int[] b = encode(hi);
        int[] seq = new int[a.length * 2];
        for (int i = 0; i < a.length; i++) {
            seq[i * 2] = a[i];
            seq[i * 2 + 1] = b[i];
        }
        out.add(seq);
    }

    static int encodedLength(int cp) {
        if (cp <= 0x7F) {
            return 1;
        }
        if (cp <= 0x7FF) {
            return 2;
        }
        if (cp <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    static int[] encode(int cp) {
        switch (encodedLength(cp)) {
            case 1:
                return new int[] {cp};
            case 2:
                return new int[] {0xC0 | (cp >> 6), 0x80 | (cp & 0x3F)};
            case 3:
                return new int[] {0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F)};
            default:
                return new int[] {0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F)};
        }
    }
}
