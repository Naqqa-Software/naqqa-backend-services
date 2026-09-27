package com.naqqa.elasticsearch.common.automaton;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Operations {

    public static final int DEFAULT_DETERMINIZE_WORK_LIMIT = 10000;
    public static final int DEFAULT_MAX_DETERMINIZED_STATES = 10000;

    private Operations() {
    }

    public static Automaton concatenate(Automaton a, Automaton b) {
        return concatenate(List.of(a, b));
    }

    public static Automaton concatenate(List<Automaton> list) {
        Automaton result = new Automaton();
        if (list.isEmpty()) {
            result.createState();
            result.setAccept(0, true);
            return result;
        }
        int[] offsets = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            offsets[i] = list.get(i).copyInto(result);
        }
        for (int i = 0; i + 1 < list.size(); i++) {
            Automaton a = list.get(i);
            int nextStart = offsets[i + 1];
            for (int s = 0; s < a.getNumStates(); s++) {
                if (a.isAccept(s)) {
                    result.setAccept(s + offsets[i], false);
                    result.addEpsilon(s + offsets[i], nextStart);
                }
            }
        }
        return result;
    }

    public static Automaton union(Automaton a, Automaton b) {
        return union(List.of(a, b));
    }

    public static Automaton union(Collection<Automaton> list) {
        Automaton result = new Automaton();
        result.createState();
        for (Automaton a : list) {
            int offset = a.copyInto(result);
            result.addEpsilon(0, offset);
        }
        return result;
    }

    public static Automaton optional(Automaton a) {
        Automaton result = new Automaton();
        result.createState();
        result.setAccept(0, true);
        int offset = a.copyInto(result);
        result.addEpsilon(0, offset);
        return result;
    }

    public static Automaton repeat(Automaton a) {
        Automaton result = new Automaton();
        result.createState();
        result.setAccept(0, true);
        int offset = a.copyInto(result);
        result.addEpsilon(0, offset);
        for (int s = 0; s < a.getNumStates(); s++) {
            if (a.isAccept(s)) {
                result.addEpsilon(s + offset, 0);
            }
        }
        return result;
    }

    public static Automaton repeat(Automaton a, int min) {
        if (min < 0) {
            throw new IllegalArgumentException("min must be >= 0");
        }
        List<Automaton> parts = new ArrayList<>();
        for (int i = 0; i < min; i++) {
            parts.add(a);
        }
        parts.add(repeat(a));
        return concatenate(parts);
    }

    public static Automaton repeat(Automaton a, int min, int max) {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("invalid repeat bounds {" + min + "," + max + "}");
        }
        List<Automaton> parts = new ArrayList<>();
        for (int i = 0; i < min; i++) {
            parts.add(a);
        }
        if (max > min) {
            Automaton tail = optional(a);
            for (int i = min + 1; i < max; i++) {
                tail = optional(concatenate(a, tail));
            }
            parts.add(tail);
        }
        return concatenate(parts);
    }

    public static Automaton removeEpsilons(Automaton a) {
        if (!a.hasEpsilons()) {
            return a;
        }
        int n = a.getNumStates();
        Automaton result = new Automaton();
        for (int s = 0; s < n; s++) {
            result.createState();
        }
        int[] marks = new int[n];
        int gen = 0;
        int[] stack = new int[n];
        for (int s = 0; s < n; s++) {
            gen++;
            int sp = 0;
            stack[sp++] = s;
            marks[s] = gen;
            boolean accept = false;
            while (sp > 0) {
                int q = stack[--sp];
                if (a.isAccept(q)) {
                    accept = true;
                }
                int nt = a.getNumTransitions(q);
                for (int i = 0; i < nt; i++) {
                    result.addTransition(s, a.getDest(q, i), a.getMin(q, i), a.getMax(q, i));
                }
                int ne = a.getNumEpsilons(q);
                for (int i = 0; i < ne; i++) {
                    int d = a.getEpsilon(q, i);
                    if (marks[d] != gen) {
                        marks[d] = gen;
                        stack[sp++] = d;
                    }
                }
            }
            result.setAccept(s, accept);
        }
        result.finish();
        return removeDeadStates(result);
    }

    public static Automaton determinize(Automaton a, int maxDeterminizedStates) {
        if (a.isDeterministic()) {
            return a;
        }
        int n = a.getNumStates();
        Automaton result = new Automaton();
        Map<StateSet, Integer> ids = new HashMap<>();
        List<int[]> sets = new ArrayList<>();
        int[] marks = new int[n];
        int[] genHolder = {0};
        int[] stack = new int[n];
        int[] initial = closure(a, new int[] {0}, 1, marks, genHolder, stack);
        StateSet initialKey = new StateSet(initial);
        ids.put(initialKey, 0);
        sets.add(initial);
        result.createState();
        result.setAccept(0, anyAccept(a, initial));
        int[] counts = new int[n];
        int[] active = new int[n];
        int[] activePos = new int[n];
        long[] events = new long[16];
        int[] tmp = new int[n];
        for (int cur = 0; cur < sets.size(); cur++) {
            int[] set = sets.get(cur);
            int ne = 0;
            for (int q : set) {
                int nt = a.getNumTransitions(q);
                for (int i = 0; i < nt; i++) {
                    if (ne + 2 > events.length) {
                        events = Arrays.copyOf(events, events.length * 2);
                    }
                    int dest = a.getDest(q, i);
                    events[ne++] = ((long) a.getMin(q, i) << 33) | (1L << 32) | dest;
                    events[ne++] = ((long) (a.getMax(q, i) + 1) << 33) | dest;
                }
            }
            if (ne == 0) {
                continue;
            }
            Arrays.sort(events, 0, ne);
            int activeCount = 0;
            int i = 0;
            while (i < ne) {
                int point = (int) (events[i] >>> 33);
                while (i < ne && (int) (events[i] >>> 33) == point) {
                    long ev = events[i];
                    int dest = (int) (ev & 0xFFFFFFFFL);
                    boolean start = (ev & (1L << 32)) != 0;
                    if (start) {
                        if (counts[dest]++ == 0) {
                            activePos[dest] = activeCount;
                            active[activeCount++] = dest;
                        }
                    } else {
                        if (--counts[dest] == 0) {
                            int pos = activePos[dest];
                            int last = active[--activeCount];
                            active[pos] = last;
                            activePos[last] = pos;
                        }
                    }
                    i++;
                }
                if (activeCount > 0 && i < ne) {
                    int nextPoint = (int) (events[i] >>> 33);
                    System.arraycopy(active, 0, tmp, 0, activeCount);
                    int[] destSet = closure(a, tmp, activeCount, marks, genHolder, stack);
                    StateSet key = new StateSet(destSet);
                    Integer id = ids.get(key);
                    if (id == null) {
                        if (sets.size() >= maxDeterminizedStates) {
                            throw new TooComplexToDeterminizeException(maxDeterminizedStates);
                        }
                        id = result.createState();
                        result.setAccept(id, anyAccept(a, destSet));
                        ids.put(key, id);
                        sets.add(destSet);
                    }
                    result.addTransition(cur, id, point, nextPoint - 1);
                }
            }
        }
        result.finish();
        return result;
    }

    private static boolean anyAccept(Automaton a, int[] set) {
        for (int q : set) {
            if (a.isAccept(q)) {
                return true;
            }
        }
        return false;
    }

    private static int[] closure(Automaton a, int[] seeds, int count, int[] marks, int[] genHolder, int[] stack) {
        int gen = ++genHolder[0];
        int sp = 0;
        int[] out = new int[Math.max(4, count)];
        int outLen = 0;
        for (int i = 0; i < count; i++) {
            int s = seeds[i];
            if (marks[s] != gen) {
                marks[s] = gen;
                stack[sp++] = s;
            }
        }
        while (sp > 0) {
            int q = stack[--sp];
            if (outLen == out.length) {
                out = Arrays.copyOf(out, out.length * 2);
            }
            out[outLen++] = q;
            int ne = a.getNumEpsilons(q);
            for (int i = 0; i < ne; i++) {
                int d = a.getEpsilon(q, i);
                if (marks[d] != gen) {
                    marks[d] = gen;
                    stack[sp++] = d;
                }
            }
        }
        int[] result = Arrays.copyOf(out, outLen);
        Arrays.sort(result);
        return result;
    }

    private static final class StateSet {
        final int[] states;
        final int hash;

        StateSet(int[] states) {
            this.states = states;
            this.hash = Arrays.hashCode(states);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StateSet other && Arrays.equals(states, other.states);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    public static Automaton removeDeadStates(Automaton a) {
        int n = a.getNumStates();
        if (n == 0) {
            return Automata.makeEmpty();
        }
        BitSet live = getLiveStates(a);
        Automaton result = new Automaton();
        int[] map = new int[n];
        Arrays.fill(map, -1);
        map[0] = result.createState();
        for (int s = 1; s < n; s++) {
            if (live.get(s)) {
                map[s] = result.createState();
            }
        }
        for (int s = 0; s < n; s++) {
            if (map[s] == -1) {
                continue;
            }
            result.setAccept(map[s], a.isAccept(s));
            if (!live.get(s)) {
                continue;
            }
            int nt = a.getNumTransitions(s);
            for (int i = 0; i < nt; i++) {
                int d = a.getDest(s, i);
                if (live.get(d)) {
                    result.addTransition(map[s], map[d], a.getMin(s, i), a.getMax(s, i));
                }
            }
            int ne = a.getNumEpsilons(s);
            for (int i = 0; i < ne; i++) {
                int d = a.getEpsilon(s, i);
                if (live.get(d)) {
                    result.addEpsilon(map[s], map[d]);
                }
            }
        }
        result.finish();
        return result;
    }

    public static BitSet getLiveStates(Automaton a) {
        BitSet reachable = getReachableStates(a);
        BitSet coReachable = getCoReachableStates(a);
        reachable.and(coReachable);
        return reachable;
    }

    public static BitSet getReachableStates(Automaton a) {
        int n = a.getNumStates();
        BitSet seen = new BitSet(n);
        if (n == 0) {
            return seen;
        }
        int[] stack = new int[n];
        int sp = 0;
        stack[sp++] = 0;
        seen.set(0);
        while (sp > 0) {
            int s = stack[--sp];
            int nt = a.getNumTransitions(s);
            for (int i = 0; i < nt; i++) {
                int d = a.getDest(s, i);
                if (!seen.get(d)) {
                    seen.set(d);
                    stack[sp++] = d;
                }
            }
            int ne = a.getNumEpsilons(s);
            for (int i = 0; i < ne; i++) {
                int d = a.getEpsilon(s, i);
                if (!seen.get(d)) {
                    seen.set(d);
                    stack[sp++] = d;
                }
            }
        }
        return seen;
    }

    public static BitSet getCoReachableStates(Automaton a) {
        int n = a.getNumStates();
        int[] revCount = new int[n + 1];
        for (int s = 0; s < n; s++) {
            int nt = a.getNumTransitions(s);
            for (int i = 0; i < nt; i++) {
                revCount[a.getDest(s, i) + 1]++;
            }
            int ne = a.getNumEpsilons(s);
            for (int i = 0; i < ne; i++) {
                revCount[a.getEpsilon(s, i) + 1]++;
            }
        }
        for (int i = 0; i < n; i++) {
            revCount[i + 1] += revCount[i];
        }
        int[] rev = new int[revCount[n]];
        int[] fill = Arrays.copyOf(revCount, n);
        for (int s = 0; s < n; s++) {
            int nt = a.getNumTransitions(s);
            for (int i = 0; i < nt; i++) {
                rev[fill[a.getDest(s, i)]++] = s;
            }
            int ne = a.getNumEpsilons(s);
            for (int i = 0; i < ne; i++) {
                rev[fill[a.getEpsilon(s, i)]++] = s;
            }
        }
        BitSet seen = new BitSet(n);
        int[] stack = new int[n];
        int sp = 0;
        for (int s = 0; s < n; s++) {
            if (a.isAccept(s)) {
                seen.set(s);
                stack[sp++] = s;
            }
        }
        while (sp > 0) {
            int s = stack[--sp];
            for (int i = revCount[s]; i < revCount[s + 1]; i++) {
                int p = rev[i];
                if (!seen.get(p)) {
                    seen.set(p);
                    stack[sp++] = p;
                }
            }
        }
        return seen;
    }

    public static Automaton totalize(Automaton a, int maxLabel) {
        a = removeEpsilons(a);
        Automaton result = new Automaton();
        int n = a.getNumStates();
        for (int s = 0; s < n; s++) {
            result.createState();
            result.setAccept(s, a.isAccept(s));
        }
        int sink = result.createState();
        result.addTransition(sink, sink, 0, maxLabel);
        for (int s = 0; s < n; s++) {
            int next = 0;
            int nt = a.getNumTransitions(s);
            for (int i = 0; i < nt; i++) {
                int min = a.getMin(s, i);
                int max = Math.min(a.getMax(s, i), maxLabel);
                if (min > maxLabel) {
                    continue;
                }
                result.addTransition(s, a.getDest(s, i), min, max);
                if (min > next) {
                    result.addTransition(s, sink, next, min - 1);
                }
                if (max + 1 > next) {
                    next = max + 1;
                }
            }
            if (next <= maxLabel) {
                result.addTransition(s, sink, next, maxLabel);
            }
        }
        result.finish();
        return result;
    }

    public static Automaton complement(Automaton a, int maxDeterminizedStates) {
        return complement(a, Automaton.MAX_CODE_POINT, maxDeterminizedStates);
    }

    public static Automaton complement(Automaton a, int maxLabel, int maxDeterminizedStates) {
        a = totalize(determinize(removeEpsilons(a), maxDeterminizedStates), maxLabel);
        Automaton result = a.copy();
        for (int s = 0; s < result.getNumStates(); s++) {
            result.setAccept(s, !result.isAccept(s));
        }
        return removeDeadStates(result);
    }

    public static Automaton minus(Automaton a, Automaton b, int maxDeterminizedStates) {
        if (isEmpty(a) || a == b) {
            return Automata.makeEmpty();
        }
        if (isEmpty(b)) {
            return a;
        }
        int maxLabel = Math.max(Math.max(a.maxLabel(), b.maxLabel()), 0);
        int alphabet = maxLabel <= Automaton.MAX_BYTE ? Automaton.MAX_BYTE : Automaton.MAX_CODE_POINT;
        return intersection(a, complement(b, alphabet, maxDeterminizedStates));
    }

    public static Automaton intersection(Automaton a1, Automaton a2) {
        a1 = removeEpsilons(a1);
        a2 = removeEpsilons(a2);
        Automaton result = new Automaton();
        Map<Long, Integer> ids = new HashMap<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        result.createState();
        result.setAccept(0, a1.isAccept(0) && a2.isAccept(0));
        ids.put(0L, 0);
        queue.add(new long[] {0, 0, 0});
        while (!queue.isEmpty()) {
            long[] p = queue.poll();
            int s1 = (int) p[0];
            int s2 = (int) p[1];
            int id = (int) p[2];
            int n1 = a1.getNumTransitions(s1);
            int n2 = a2.getNumTransitions(s2);
            int b = 0;
            for (int i = 0; i < n1; i++) {
                int min1 = a1.getMin(s1, i);
                int max1 = a1.getMax(s1, i);
                while (b < n2 && a2.getMax(s2, b) < min1) {
                    b++;
                }
                for (int j = b; j < n2 && a2.getMin(s2, j) <= max1; j++) {
                    int max2 = a2.getMax(s2, j);
                    if (max2 < min1) {
                        continue;
                    }
                    int d1 = a1.getDest(s1, i);
                    int d2 = a2.getDest(s2, j);
                    long key = ((long) d1 << 32) | (d2 & 0xFFFFFFFFL);
                    Integer dest = ids.get(key);
                    if (dest == null) {
                        dest = result.createState();
                        result.setAccept(dest, a1.isAccept(d1) && a2.isAccept(d2));
                        ids.put(key, dest);
                        queue.add(new long[] {d1, d2, dest});
                    }
                    result.addTransition(id, dest, Math.max(min1, a2.getMin(s2, j)), Math.min(max1, max2));
                }
            }
        }
        result.finish();
        return removeDeadStates(result);
    }

    public static Automaton minimize(Automaton a, int maxDeterminizedStates) {
        a = determinize(removeEpsilons(a), maxDeterminizedStates);
        a = removeDeadStates(a);
        if (a.getNumTransitions() == 0) {
            Automaton r = new Automaton();
            r.createState();
            r.setAccept(0, a.isAccept(0));
            return r;
        }
        int maxLabel = a.maxLabel() <= Automaton.MAX_BYTE ? Automaton.MAX_BYTE : Automaton.MAX_CODE_POINT;
        a = totalize(a, maxLabel);
        int n = a.getNumStates();
        int[] sigma = alphabetPoints(a, maxLabel);
        int k = sigma.length;
        int[] delta = new int[n * k];
        for (int s = 0; s < n; s++) {
            int nt = a.getNumTransitions(s);
            int t = 0;
            for (int c = 0; c < k; c++) {
                int label = sigma[c];
                while (t < nt && a.getMax(s, t) < label) {
                    t++;
                }
                delta[s * k + c] = a.getDest(s, t);
            }
        }
        int[] revStart = new int[k * (n + 1) + 1];
        for (int s = 0; s < n; s++) {
            for (int c = 0; c < k; c++) {
                revStart[c * (n + 1) + delta[s * k + c] + 1]++;
            }
        }
        for (int i = 0; i < revStart.length - 1; i++) {
            revStart[i + 1] += revStart[i];
        }
        int[] rev = new int[n * k];
        int[] fill = Arrays.copyOf(revStart, revStart.length);
        for (int s = 0; s < n; s++) {
            for (int c = 0; c < k; c++) {
                int idx = c * (n + 1) + delta[s * k + c];
                rev[fill[idx]++] = s;
            }
        }
        int[] elems = new int[n];
        int[] loc = new int[n];
        int[] sidx = new int[n];
        int[] first = new int[n + 1];
        int[] end = new int[n + 1];
        int[] mid = new int[n + 1];
        int numBlocks = 0;
        int pos = 0;
        for (int pass = 0; pass < 2; pass++) {
            int startPos = pos;
            for (int s = 0; s < n; s++) {
                if (a.isAccept(s) == (pass == 0)) {
                    elems[pos] = s;
                    loc[s] = pos;
                    sidx[s] = numBlocks;
                    pos++;
                }
            }
            if (pos > startPos) {
                first[numBlocks] = startPos;
                end[numBlocks] = pos;
                mid[numBlocks] = startPos;
                numBlocks++;
            }
        }
        boolean[] inW = new boolean[n + 1];
        int[] worklist = new int[n + 1];
        int wHead = 0;
        int wSize = 0;
        if (numBlocks == 2) {
            int smaller = (end[0] - first[0]) <= (end[1] - first[1]) ? 0 : 1;
            worklist[wSize++] = smaller;
            inW[smaller] = true;
        } else {
            worklist[wSize++] = 0;
            inW[0] = true;
        }
        int[] touched = new int[n + 1];
        int[] members = new int[n];
        while (wHead < wSize) {
            int splitter = worklist[wHead % (n + 1)];
            wHead++;
            inW[splitter] = false;
            int mcount = end[splitter] - first[splitter];
            System.arraycopy(elems, first[splitter], members, 0, mcount);
            for (int c = 0; c < k; c++) {
                int touchedCount = 0;
                int base = c * (n + 1);
                for (int m = 0; m < mcount; m++) {
                    int s = members[m];
                    for (int r = revStart[base + s]; r < revStart[base + s + 1]; r++) {
                        int p = rev[r];
                        int b = sidx[p];
                        int i = loc[p];
                        int j = mid[b];
                        if (i >= j) {
                            if (j == first[b]) {
                                touched[touchedCount++] = b;
                            }
                            int other = elems[j];
                            elems[j] = p;
                            loc[p] = j;
                            elems[i] = other;
                            loc[other] = i;
                            mid[b] = j + 1;
                        }
                    }
                }
                for (int t = 0; t < touchedCount; t++) {
                    int b = touched[t];
                    if (mid[b] == end[b]) {
                        mid[b] = first[b];
                        continue;
                    }
                    int nb = numBlocks++;
                    first[nb] = first[b];
                    end[nb] = mid[b];
                    mid[nb] = first[nb];
                    first[b] = mid[b];
                    mid[b] = first[b];
                    for (int e = first[nb]; e < end[nb]; e++) {
                        sidx[elems[e]] = nb;
                    }
                    if (inW[b]) {
                        worklist[wSize % (n + 1)] = nb;
                        wSize++;
                        inW[nb] = true;
                    } else {
                        int add = (end[nb] - first[nb]) <= (end[b] - first[b]) ? nb : b;
                        worklist[wSize % (n + 1)] = add;
                        wSize++;
                        inW[add] = true;
                    }
                }
            }
        }
        Automaton result = new Automaton();
        int[] blockToState = new int[numBlocks];
        Arrays.fill(blockToState, -1);
        blockToState[sidx[0]] = result.createState();
        for (int b = 0; b < numBlocks; b++) {
            if (blockToState[b] == -1) {
                blockToState[b] = result.createState();
            }
        }
        for (int b = 0; b < numBlocks; b++) {
            int rep = elems[first[b]];
            int st = blockToState[b];
            result.setAccept(st, a.isAccept(rep));
            for (int c = 0; c < k; c++) {
                int dest = blockToState[sidx[delta[rep * k + c]]];
                int hi = c + 1 < k ? sigma[c + 1] - 1 : maxLabel;
                result.addTransition(st, dest, sigma[c], hi);
            }
        }
        result.finish();
        return removeDeadStates(result);
    }

    private static int[] alphabetPoints(Automaton a, int maxLabel) {
        Set<Integer> points = new java.util.TreeSet<>();
        points.add(0);
        for (int s = 0; s < a.getNumStates(); s++) {
            int nt = a.getNumTransitions(s);
            for (int i = 0; i < nt; i++) {
                points.add(a.getMin(s, i));
                int m = a.getMax(s, i);
                if (m < maxLabel) {
                    points.add(m + 1);
                }
            }
        }
        int[] out = new int[points.size()];
        int i = 0;
        for (int p : points) {
            out[i++] = p;
        }
        return out;
    }

    public static boolean isEmpty(Automaton a) {
        if (a.getNumStates() == 0) {
            return true;
        }
        BitSet reach = getReachableStates(a);
        for (int s = reach.nextSetBit(0); s >= 0; s = reach.nextSetBit(s + 1)) {
            if (a.isAccept(s)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isTotal(Automaton a) {
        return isTotal(a, 0, Automaton.MAX_CODE_POINT);
    }

    public static boolean isTotal(Automaton a, int minLabel, int maxLabel) {
        Automaton d = determinize(removeEpsilons(a), DEFAULT_MAX_DETERMINIZED_STATES);
        if (minLabel != 0) {
            throw new IllegalArgumentException("minLabel must be 0");
        }
        d = totalize(d, maxLabel);
        BitSet reach = getReachableStates(d);
        for (int s = reach.nextSetBit(0); s >= 0; s = reach.nextSetBit(s + 1)) {
            if (!d.isAccept(s)) {
                return false;
            }
        }
        return true;
    }

    public static boolean run(Automaton a, String s) {
        return run(a, s.codePoints().toArray());
    }

    public static boolean run(Automaton a, int[] labels) {
        if (a.isDeterministic()) {
            int state = 0;
            for (int label : labels) {
                state = a.step(state, label);
                if (state == -1) {
                    return false;
                }
            }
            return a.isAccept(state);
        }
        int n = a.getNumStates();
        int[] marks = new int[n];
        int[] gen = {0};
        int[] stack = new int[n];
        int[] current = closure(a, new int[] {0}, 1, marks, gen, stack);
        int[] next = new int[n];
        for (int label : labels) {
            int cnt = 0;
            int g = ++gen[0];
            for (int q : current) {
                int nt = a.getNumTransitions(q);
                for (int i = 0; i < nt; i++) {
                    if (a.getMin(q, i) <= label && label <= a.getMax(q, i)) {
                        int d = a.getDest(q, i);
                        if (marks[d] != g) {
                            marks[d] = g;
                            next[cnt++] = d;
                        }
                    }
                }
            }
            if (cnt == 0) {
                return false;
            }
            current = closure(a, next, cnt, marks, gen, stack);
        }
        return anyAccept(a, current);
    }

    public static boolean run(Automaton a, byte[] bytes) {
        int[] labels = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            labels[i] = bytes[i] & 0xFF;
        }
        return run(a, labels);
    }

    public static boolean subsetOf(Automaton a, Automaton b) {
        return isEmpty(minus(a, b, Integer.MAX_VALUE));
    }

    public static boolean sameLanguage(Automaton a, Automaton b) {
        return subsetOf(a, b) && subsetOf(b, a);
    }

    public static int[] getCommonPrefixLabels(Automaton a) {
        a = determinize(removeEpsilons(a), DEFAULT_MAX_DETERMINIZED_STATES);
        a = removeDeadStates(a);
        List<Integer> labels = new ArrayList<>();
        BitSet visited = new BitSet();
        int s = 0;
        while (!a.isAccept(s) && !visited.get(s) && a.getNumTransitions(s) == 1 && a.getMin(s, 0) == a.getMax(s, 0)) {
            visited.set(s);
            labels.add(a.getMin(s, 0));
            s = a.getDest(s, 0);
        }
        int[] out = new int[labels.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = labels.get(i);
        }
        return out;
    }

    public static String getCommonPrefix(Automaton a) {
        int[] labels = getCommonPrefixLabels(a);
        return new String(labels, 0, labels.length);
    }

    public static byte[] getCommonPrefixBytes(Automaton a) {
        int[] labels = getCommonPrefixLabels(a);
        byte[] out = new byte[labels.length];
        for (int i = 0; i < labels.length; i++) {
            out[i] = (byte) labels[i];
        }
        return out;
    }

    public static int[] getSingleton(Automaton a) {
        a = determinize(removeEpsilons(a), DEFAULT_MAX_DETERMINIZED_STATES);
        a = removeDeadStates(a);
        if (isEmpty(a)) {
            return null;
        }
        List<Integer> labels = new ArrayList<>();
        BitSet visited = new BitSet();
        int s = 0;
        while (true) {
            if (visited.get(s)) {
                return null;
            }
            visited.set(s);
            int nt = a.getNumTransitions(s);
            if (a.isAccept(s)) {
                if (nt != 0) {
                    return null;
                }
                int[] out = new int[labels.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = labels.get(i);
                }
                return out;
            }
            if (nt != 1 || a.getMin(s, 0) != a.getMax(s, 0)) {
                return null;
            }
            labels.add(a.getMin(s, 0));
            s = a.getDest(s, 0);
        }
    }

    public static boolean isFinite(Automaton a) {
        a = removeDeadStates(removeEpsilons(a));
        int n = a.getNumStates();
        if (n == 0) {
            return true;
        }
        byte[] color = new byte[n];
        int[] stack = new int[n];
        int[] iter = new int[n];
        int sp = 0;
        stack[sp++] = 0;
        color[0] = 1;
        while (sp > 0) {
            int s = stack[sp - 1];
            if (iter[s] < a.getNumTransitions(s)) {
                int d = a.getDest(s, iter[s]++);
                if (color[d] == 1) {
                    return false;
                }
                if (color[d] == 0) {
                    color[d] = 1;
                    stack[sp++] = d;
                }
            } else {
                color[s] = 2;
                sp--;
            }
        }
        return true;
    }

    public static Set<int[]> getFiniteStrings(Automaton a, int limit) {
        a = removeDeadStates(determinize(removeEpsilons(a), DEFAULT_MAX_DETERMINIZED_STATES));
        Set<int[]> out = new LinkedHashSet<>();
        if (isEmpty(a)) {
            return out;
        }
        int[] path = new int[16];
        collectFinite(a, 0, path, 0, new BitSet(), out, limit);
        return out;
    }

    private static int[] collectFinite(Automaton a, int s, int[] path, int depth, BitSet onPath, Set<int[]> out, int limit) {
        if (onPath.get(s)) {
            throw new IllegalArgumentException("automaton accepts an infinite language");
        }
        if (a.isAccept(s)) {
            if (limit >= 0 && out.size() >= limit) {
                throw new IllegalArgumentException("more than " + limit + " finite strings");
            }
            out.add(Arrays.copyOf(path, depth));
        }
        onPath.set(s);
        int nt = a.getNumTransitions(s);
        for (int i = 0; i < nt; i++) {
            for (int c = a.getMin(s, i); c <= a.getMax(s, i); c++) {
                if (depth == path.length) {
                    path = Arrays.copyOf(path, path.length * 2);
                }
                path[depth] = c;
                path = collectFinite(a, a.getDest(s, i), path, depth + 1, onPath, out, limit);
            }
        }
        onPath.clear(s);
        return path;
    }

    public static Automaton reverse(Automaton a) {
        a = removeEpsilons(a);
        int n = a.getNumStates();
        Automaton result = new Automaton();
        result.createState();
        for (int s = 0; s < n; s++) {
            result.createState();
        }
        for (int s = 0; s < n; s++) {
            int nt = a.getNumTransitions(s);
            for (int i = 0; i < nt; i++) {
                result.addTransition(a.getDest(s, i) + 1, s + 1, a.getMin(s, i), a.getMax(s, i));
            }
            if (a.isAccept(s)) {
                result.addEpsilon(0, s + 1);
            }
        }
        result.setAccept(1, true);
        return result;
    }
}
