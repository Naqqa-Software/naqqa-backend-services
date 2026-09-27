package com.naqqa.elasticsearch.common.automaton;

public final class TooComplexToDeterminizeException extends RuntimeException {

    private final int maxDeterminizedStates;

    public TooComplexToDeterminizeException(int maxDeterminizedStates) {
        super("Determinizing automaton would result in more than " + maxDeterminizedStates + " states.");
        this.maxDeterminizedStates = maxDeterminizedStates;
    }

    public TooComplexToDeterminizeException(String regexp, int maxDeterminizedStates) {
        super("Determinizing [" + regexp + "] would require more than " + maxDeterminizedStates + " effort.");
        this.maxDeterminizedStates = maxDeterminizedStates;
    }

    public int getMaxDeterminizedStates() {
        return maxDeterminizedStates;
    }
}
