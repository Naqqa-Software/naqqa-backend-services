package com.naqqa.elasticsearch.common.automaton;

import java.util.Arrays;

public final class Automaton {

    public static final int MAX_CODE_POINT = 0x10FFFF;
    public static final int MAX_BYTE = 0xFF;

    private int numStates;
    private boolean[] accept = new boolean[4];
    private int[][] trans = new int[4][];
    private int[] transLen = new int[4];
    private int[][] eps = new int[4][];
    private int[] epsLen = new int[4];
    private boolean[] dirty = new boolean[4];
    private int epsilonCount;
    private Boolean deterministic;

    public Automaton() {
    }

    public int createState() {
        if (numStates == accept.length) {
            int cap = Math.max(4, numStates * 2);
            accept = Arrays.copyOf(accept, cap);
            trans = Arrays.copyOf(trans, cap);
            transLen = Arrays.copyOf(transLen, cap);
            eps = Arrays.copyOf(eps, cap);
            epsLen = Arrays.copyOf(epsLen, cap);
            dirty = Arrays.copyOf(dirty, cap);
        }
        deterministic = null;
        return numStates++;
    }

    public int getNumStates() {
        return numStates;
    }

    public void setAccept(int state, boolean value) {
        checkState(state);
        accept[state] = value;
    }

    public boolean isAccept(int state) {
        checkState(state);
        return accept[state];
    }

    public void addTransition(int src, int dest, int label) {
        addTransition(src, dest, label, label);
    }

    public void addTransition(int src, int dest, int min, int max) {
        checkState(src);
        checkState(dest);
        if (min < 0 || max < min || max > MAX_CODE_POINT) {
            throw new IllegalArgumentException("invalid transition range [" + min + "," + max + "]");
        }
        int[] t = trans[src];
        int len = transLen[src];
        if (t == null) {
            t = new int[6];
            trans[src] = t;
        } else if (len + 3 > t.length) {
            t = Arrays.copyOf(t, t.length * 2);
            trans[src] = t;
        }
        t[len] = dest;
        t[len + 1] = min;
        t[len + 2] = max;
        transLen[src] = len + 3;
        dirty[src] = true;
        deterministic = null;
    }

    public void addEpsilon(int src, int dest) {
        checkState(src);
        checkState(dest);
        if (src == dest) {
            return;
        }
        int[] e = eps[src];
        int len = epsLen[src];
        for (int i = 0; i < len; i++) {
            if (e[i] == dest) {
                return;
            }
        }
        if (e == null) {
            e = new int[2];
            eps[src] = e;
        } else if (len == e.length) {
            e = Arrays.copyOf(e, e.length * 2);
            eps[src] = e;
        }
        e[len] = dest;
        epsLen[src] = len + 1;
        epsilonCount++;
        deterministic = null;
    }

    public boolean hasEpsilons() {
        return epsilonCount > 0;
    }

    public int getNumEpsilons(int state) {
        checkState(state);
        return epsLen[state];
    }

    public int getEpsilon(int state, int index) {
        return eps[state][index];
    }

    public int getNumTransitions(int state) {
        checkState(state);
        sortState(state);
        return transLen[state] / 3;
    }

    public int getDest(int state, int index) {
        sortState(state);
        return trans[state][index * 3];
    }

    public int getMin(int state, int index) {
        sortState(state);
        return trans[state][index * 3 + 1];
    }

    public int getMax(int state, int index) {
        sortState(state);
        return trans[state][index * 3 + 2];
    }

    public int getNumTransitions() {
        int total = 0;
        for (int s = 0; s < numStates; s++) {
            total += getNumTransitions(s);
        }
        return total;
    }

    public void finish() {
        for (int s = 0; s < numStates; s++) {
            sortState(s);
        }
    }

    public boolean isDeterministic() {
        Boolean d = deterministic;
        if (d != null) {
            return d;
        }
        boolean result = epsilonCount == 0;
        if (result) {
            outer:
            for (int s = 0; s < numStates; s++) {
                sortState(s);
                int[] t = trans[s];
                int len = transLen[s];
                for (int i = 3; i < len; i += 3) {
                    if (t[i + 1] <= t[i - 1]) {
                        result = false;
                        break outer;
                    }
                }
            }
        }
        deterministic = result;
        return result;
    }

    public int step(int state, int label) {
        sortState(state);
        int[] t = trans[state];
        if (!isDeterministic()) {
            for (int i = 0; i < transLen[state]; i += 3) {
                if (t[i + 1] <= label && label <= t[i + 2]) {
                    return t[i];
                }
            }
            return -1;
        }
        int lo = 0;
        int hi = transLen[state] / 3 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int min = t[mid * 3 + 1];
            int max = t[mid * 3 + 2];
            if (label < min) {
                hi = mid - 1;
            } else if (label > max) {
                lo = mid + 1;
            } else {
                return t[mid * 3];
            }
        }
        return -1;
    }

    public int copyInto(Automaton target) {
        int offset = target.getNumStates();
        for (int s = 0; s < numStates; s++) {
            target.createState();
        }
        for (int s = 0; s < numStates; s++) {
            target.setAccept(s + offset, accept[s]);
            int[] t = trans[s];
            for (int i = 0; i < transLen[s]; i += 3) {
                target.addTransition(s + offset, t[i] + offset, t[i + 1], t[i + 2]);
            }
            for (int i = 0; i < epsLen[s]; i++) {
                target.addEpsilon(s + offset, eps[s][i] + offset);
            }
        }
        return offset;
    }

    public Automaton copy() {
        Automaton a = new Automaton();
        copyInto(a);
        return a;
    }

    public int maxLabel() {
        int max = -1;
        for (int s = 0; s < numStates; s++) {
            int[] t = trans[s];
            for (int i = 0; i < transLen[s]; i += 3) {
                max = Math.max(max, t[i + 2]);
            }
        }
        return max;
    }

    private void checkState(int state) {
        if (state < 0 || state >= numStates) {
            throw new IllegalArgumentException("state " + state + " out of bounds (numStates=" + numStates + ")");
        }
    }

    private void sortState(int s) {
        if (!dirty[s]) {
            return;
        }
        dirty[s] = false;
        int len = transLen[s];
        if (len <= 3) {
            return;
        }
        int n = len / 3;
        int[] t = trans[s];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> {
            int c = Integer.compare(t[x * 3], t[y * 3]);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(t[x * 3 + 1], t[y * 3 + 1]);
            if (c != 0) {
                return c;
            }
            return Integer.compare(t[x * 3 + 2], t[y * 3 + 2]);
        });
        int[] merged = new int[len];
        int m = 0;
        for (int k = 0; k < n; k++) {
            int i = order[k];
            int dest = t[i * 3];
            int min = t[i * 3 + 1];
            int max = t[i * 3 + 2];
            if (m > 0 && merged[m - 3] == dest && min <= merged[m - 1] + 1) {
                if (max > merged[m - 1]) {
                    merged[m - 1] = max;
                }
            } else {
                merged[m] = dest;
                merged[m + 1] = min;
                merged[m + 2] = max;
                m += 3;
            }
        }
        int cnt = m / 3;
        Integer[] order2 = new Integer[cnt];
        for (int i = 0; i < cnt; i++) {
            order2[i] = i;
        }
        final int[] mm = merged;
        Arrays.sort(order2, (x, y) -> {
            int c = Integer.compare(mm[x * 3 + 1], mm[y * 3 + 1]);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(mm[x * 3 + 2], mm[y * 3 + 2]);
            if (c != 0) {
                return c;
            }
            return Integer.compare(mm[x * 3], mm[y * 3]);
        });
        int[] out = new int[Math.max(6, m)];
        for (int k = 0; k < cnt; k++) {
            int i = order2[k];
            out[k * 3] = merged[i * 3];
            out[k * 3 + 1] = merged[i * 3 + 1];
            out[k * 3 + 2] = merged[i * 3 + 2];
        }
        trans[s] = out;
        transLen[s] = m;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Automaton{states=").append(numStates).append('\n');
        for (int s = 0; s < numStates; s++) {
            sb.append("  ").append(s).append(accept[s] ? " [accept]" : "").append(":");
            int n = getNumTransitions(s);
            for (int i = 0; i < n; i++) {
                sb.append(' ').append(label(getMin(s, i)));
                if (getMax(s, i) != getMin(s, i)) {
                    sb.append('-').append(label(getMax(s, i)));
                }
                sb.append("->").append(getDest(s, i));
            }
            for (int i = 0; i < epsLen[s]; i++) {
                sb.append(" eps->").append(eps[s][i]);
            }
            sb.append('\n');
        }
        return sb.append('}').toString();
    }

    private static String label(int c) {
        if (c >= 0x21 && c <= 0x7e && c != '\\') {
            return String.valueOf((char) c);
        }
        return "\\u" + Integer.toHexString(c);
    }
}
