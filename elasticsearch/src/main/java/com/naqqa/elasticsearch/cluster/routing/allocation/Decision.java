package com.naqqa.elasticsearch.cluster.routing.allocation;

import java.util.ArrayList;
import java.util.List;

public final class Decision {

    public enum Type {
        YES, THROTTLE, NO
    }

    public static final Decision YES = new Decision(Type.YES, "default", "yes");
    public static final Decision NO = new Decision(Type.NO, "default", "no");
    public static final Decision THROTTLE = new Decision(Type.THROTTLE, "default", "throttle");

    private final Type type;
    private final String label;
    private final String explanation;

    public Decision(Type type, String label, String explanation) {
        this.type = type;
        this.label = label;
        this.explanation = explanation;
    }

    public static Decision single(Type type, String label, String explanationFormat, Object... args) {
        return new Decision(type, label, args.length == 0 ? explanationFormat : String.format(explanationFormat, args));
    }

    public Type type() {
        return type;
    }

    public String label() {
        return label;
    }

    public String explanation() {
        return explanation;
    }

    @Override
    public String toString() {
        return type + "(" + label + "): " + explanation;
    }

    public static final class Multi {
        private final List<Decision> decisions = new ArrayList<>();
        private Type worst = Type.YES;

        public Multi add(Decision decision) {
            decisions.add(decision);
            if (decision.type == Type.NO) {
                worst = Type.NO;
            } else if (decision.type == Type.THROTTLE && worst != Type.NO) {
                worst = Type.THROTTLE;
            }
            return this;
        }

        public Type type() {
            return worst;
        }

        public List<Decision> getDecisions() {
            return decisions;
        }
    }
}
