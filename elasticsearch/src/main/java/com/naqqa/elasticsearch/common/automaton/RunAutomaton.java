package com.naqqa.elasticsearch.common.automaton;

import java.util.Arrays;
import java.util.TreeSet;

public abstract class RunAutomaton {

    final Automaton automaton;
    final int alphabetSize;
    final int size;
    final boolean[] accept;
    final int[] transitions;
    final int[] points;
    final int[] classmap;

    protected RunAutomaton(Automaton a, int alphabetSize, int maxDeterminizedStates) {
        this.alphabetSize = alphabetSize;
        Automaton d = Operations.removeDeadStates(Operations.determinize(Operations.removeEpsilons(a), maxDeterminizedStates));
        this.automaton = d;
        this.size = Math.max(1, d.getNumStates());
        TreeSet<Integer> pts = new TreeSet<>();
        pts.add(0);
        for (int s = 0; s < d.getNumStates(); s++) {
            int nt = d.getNumTransitions(s);
            for (int i = 0; i < nt; i++) {
                pts.add(d.getMin(s, i));
                if (d.getMax(s, i) + 1 < alphabetSize) {
                    pts.add(d.getMax(s, i) + 1);
                }
            }
        }
        this.points = new int[pts.size()];
        int k = 0;
        for (int p : pts) {
            points[k++] = p;
        }
        this.accept = new boolean[size];
        this.transitions = new int[size * points.length];
        Arrays.fill(transitions, -1);
        for (int s = 0; s < d.getNumStates(); s++) {
            accept[s] = d.isAccept(s);
            for (int c = 0; c < points.length; c++) {
                int dest = d.step(s, points[c]);
                transitions[s * points.length + c] = dest;
            }
        }
        int mapSize = Math.min(256, alphabetSize);
        this.classmap = new int[mapSize];
        int idx = 0;
        for (int j = 0; j < mapSize; j++) {
            if (idx + 1 < points.length && j == points[idx + 1]) {
                idx++;
            }
            classmap[j] = idx;
        }
    }

    final int getCharClass(int c) {
        if (c < classmap.length) {
            return classmap[c];
        }
        int lo = 0;
        int hi = points.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (points[mid] <= c) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    public final int step(int state, int c) {
        return transitions[state * points.length + getCharClass(c)];
    }

    public final boolean isAccept(int state) {
        return accept[state];
    }

    public final int getSize() {
        return size;
    }

    public final int[] getCharIntervals() {
        return points.clone();
    }

    public final Automaton getAutomaton() {
        return automaton;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{states=" + size + ", points=" + points.length + "}";
    }
}
